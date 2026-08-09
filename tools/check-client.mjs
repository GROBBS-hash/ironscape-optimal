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
if ((reasons.length || suspects.length) && fs.existsSync(LOG)) {
  const age = Date.now() - fs.statSync(LOG).mtimeMs;
  if (age < FRESH_MS) {
    const tail = fs.readFileSync(LOG, 'utf8').slice(-20000);
    const fromClient = tail.split('\n')
      .filter((l) => l.includes('com.ironscape') && l.includes('[Client]'));
    if (fromClient.length) {
      reasons.push(`client.log wrote com.ironscape [Client] lines ${Math.round(age / 1000)}s ago`
        + ' — so one of the processes above is running our plugin');
      // Now the suspect is convicted: something with our plugin is live,
      // and an installed RuneLite is the only candidate left.
      reasons.push(...suspects);
    }
  }
}

if (reasons.length) {
  console.log('RUNNING — do not run gradle:');
  reasons.forEach((r) => console.log('  - ' + r));
  process.exit(1);
}
console.log('CLEAR — no client detected; gradle is safe.');
