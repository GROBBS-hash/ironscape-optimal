#!/usr/bin/env node
// Is a client with our plugin running RIGHT NOW?
//
// Run this before EVERY gradle command. Not just `run` — the dev client
// loads classes lazily out of build/classes, so recompiling under a live
// one swaps files beneath it and the panel dies with NoClassDefFoundError.
//
// It exists because eyeballing the process list keeps going wrong, and in
// two different directions:
//
//   1. `tasklist | grep RuneLite` MISSES the dev client entirely. A
//      `gradlew run` client is java.exe; grepping for "RuneLite" returns
//      zero while one is very much alive. That check reported CLEAR and a
//      second client was launched on top of the owner's (2026-08-09).
//   2. `RuneLite.exe` is not automatically safe either. The note that his
//      installed launcher "does not use build/" turned out to be wrong
//      this session — a RuneLite.exe was writing com.ironscape log lines,
//      so it had our plugin loaded too.
//
// So process names alone cannot answer it. The reliable signal is the
// LOG: if anything has written com.ironscape lines in the last couple of
// minutes, a client with our plugin is live, whatever it is called.
//
//   node tools/check-client.mjs     -> prints CLEAR or RUNNING; exit 1 if running

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execSync } from 'node:child_process';

const LOG = path.join(os.homedir(), '.runelite', 'logs', 'client.log');
const FRESH_MS = 120_000;

const reasons = [];
/** Processes that MIGHT be running our plugin; only the log can say. */
const suspects = [];
// Did we see an actual dev-client PROCESS? Log evidence alone cannot
// distinguish "is running" from "was running until a moment ago".
let devClientProcess = false;

// 1. Any java process whose command line mentions this project or the
//    plugin's launcher class. Gradle DAEMONS are excluded by name.
try {
  const ps = execSync(
    'powershell -NoProfile -Command "Get-CimInstance Win32_Process '
    + '-Filter \\"Name=\'java.exe\' or Name=\'javaw.exe\' or Name=\'RuneLite.exe\'\\" '
    + '| Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"',
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  const list = JSON.parse(ps || '[]');
  for (const proc of [].concat(list)) {
    const cmd = proc.CommandLine || '';
    if (/GradleDaemon|gradle-launcher/i.test(cmd)) {
      continue; // a daemon compiles; it is not a client
    }
    if (/org\.gradle\.wrapper/i.test(cmd)) {
      // The `gradlew run` launcher itself. Its classpath names this project,
      // so it matched the client test below and one live client reported as
      // TWO — which reads exactly like the wave 11 accident where a stray
      // second client really was left running. Name it for what it is.
      reasons.push(`pid ${proc.ProcessId} is the gradlew launcher (parent of a run, not a second client)`);
      continue;
    }
    if (/IronscapePluginTest|IRONMAN Guide|ironscape/i.test(cmd)) {
      // Loads classes out of build/. Blocking on its own, no corroboration
      // needed — this is the process the whole check exists for.
      reasons.push(`pid ${proc.ProcessId} looks like our dev client`);
      devClientProcess = true;
    } else if (/RuneLite\.exe/i.test(proc.CommandLine || '')) {
      // The INSTALLED launcher. It only matters if it has our plugin
      // loaded, which its name cannot tell us — so it is a SUSPECT, and
      // the log below decides. Treating it as blocking by itself meant the
      // owner's everyday client blocked every build forever: the check
      // said RUNNING with no dev client anywhere, which is a false block,
      // and a check that false-blocks is one you learn to override.
      suspects.push(`pid ${proc.ProcessId} is the installed RuneLite`);
    }
  }
} catch {
  reasons.push('could not read the process list — assume a client is running');
}

// 2. Recent plugin output in the log — CORROBORATION, never proof on its
//    own. A log file cannot run: lines written seconds ago are equally
//    consistent with a client that has just exited, and on its first real
//    outing this check blocked a launch on 70-second-old lines from a
//    client that was already gone. So it only speaks when a candidate
//    process exists, where it settles the question the process list
//    cannot answer: is that process one of OURS?
//
//    Only [Client] lines count. `gradlew test` logs to this SAME file as
//    [Test worker], so matching "com.ironscape" anywhere made this check
//    circular: run the tests, and the next check reads its own output back
//    as evidence of a live client. That happened for real on 2026-08-09 —
//    a worktree test run, plus the installed RuneLite.exe standing in as
//    the candidate process, produced a confident RUNNING with no client
//    running at all. A false block is not a safe failure: it is how you
//    learn to ignore the check.
//    The age must come off the LINE, not off the file. The file's mtime is
//    written by whatever logged last, and third-party plugins log constantly
//    (worldhopper pings, DoinkOink every tick), so mtime answers "is SOME
//    client running" — the question the process list already asked. On
//    2026-08-09 that reported our plugin as 117s old when its newest line
//    was 47 minutes old: a ping kept the file warm while a stale line sat in
//    the tail window. Both failure directions follow from it — a stale line
//    plus the owner's everyday client is a false BLOCK, and one stack trace
//    can push our lines out of a fixed byte window for a false CLEAR. So:
//    read a window big enough to survive stack-trace spam, take the LAST
//    matching line, and time that.
/** Timestamp of the newest com.ironscape [Client] line, or null. */
function newestPluginLineTime() {
  const size = fs.statSync(LOG).size;
  const want = Math.min(size, 2_000_000);
  const fd = fs.openSync(LOG, 'r');
  const buf = Buffer.alloc(want);
  fs.readSync(fd, buf, 0, want, size - want);
  fs.closeSync(fd);

  let found = null;
  for (const line of buf.toString('utf8').split('\n')) {
    if (!line.includes('com.ironscape') || !line.includes('[Client]')) continue;
    const stamp = /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})/.exec(line);
    if (!stamp) continue;
    // The client writes local time; parsing it as local compares like for like.
    const at = new Date(`${stamp[1]}T${stamp[2]}`).getTime();
    if (!Number.isNaN(at) && (found === null || at > found)) found = at;
  }
  return found;
}

if ((reasons.length || suspects.length) && fs.existsSync(LOG)) {
  let newest = newestPluginLineTime();

  if (newest !== null) {
    const age = Date.now() - newest;
    // A log line is evidence that a process WAS running our plugin, not
    // that one still is. The dev client's dying line sits inside the
    // freshness window for two minutes after it exits, and with only the
    // everyday RuneLite left to blame it convicted the wrong process —
    // blocking four launches across two sessions for a minute each.
    //
    // So: sample the file again. A LIVE client keeps writing (game state,
    // nav decisions, the tick-driven caches); a dead one's last line never
    // moves. Two seconds is enough to tell them apart, and it only costs
    // that when the answer is genuinely in doubt.
    if (age < FRESH_MS && !devClientProcess) {
      const before = newest;
      // Sleep without a shell: `timeout` needs a console and `sleep` does
      // not exist on Windows, and this must work from whatever runs it.
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 2000);
      if (newestPluginLineTime() === before) {
        console.log('(note: a com.ironscape line is recent but the log has stopped growing'
          + ' and no dev client process is running — treating it as an exited client)');
        newest = null;
      }
    }
    if (newest !== null && age < FRESH_MS) {
      reasons.push(`client.log wrote a com.ironscape [Client] line ${Math.round(age / 1000)}s ago`
        + ' — so one of the processes above is running our plugin');
      // Now the suspect is convicted: something with our plugin is live,
      // and an installed RuneLite is the only candidate left.
      reasons.push(...suspects);
    } else if (suspects.length && !reasons.length) {
      // Say what was weighed. A silent CLEAR next to a live RuneLite.exe is
      // the verdict most worth being able to double-check by hand.
      console.log(`(note: ${suspects.length} RuneLite.exe process(es) seen, but our plugin`
        + ` last logged ${Math.round(age / 60000)}min ago — treating them as the everyday client)`);
    }
  }
}

if (reasons.length) {
  console.log('RUNNING — do not run gradle:');
  reasons.forEach((r) => console.log('  - ' + r));
  process.exit(1);
}
console.log('CLEAR — no client detected; gradle is safe.');
