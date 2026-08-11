#!/usr/bin/env node
// One command, one verdict: is this tree safe to put in front of the owner?
//
// Why it exists: on 2026-08-11 four separate tools turned out to disagree
// with the plugin they check — the errand audit did not know about bit
// gates, the manual-steps audit did not know a step can tick by arriving,
// an ad-hoc name check did not know charge suffixes are stripped, and
// preflight's row labels had been off by one for a session. Every one was
// found BY ACCIDENT, in the middle of doing something else. A drifted
// check is worse than no check, because its clean output is read as
// evidence.
//
// So: run everything, print one line each, and end with a verdict. Fast
// checks first so a failure surfaces early.
//
//   node tools/check-all.mjs            # audits only (safe while playing)
//   node tools/check-all.mjs --tests    # also run gradlew test
//
// The unit tests are OPT-IN because gradle must never run under a live
// client (it rewrites the classes it is running from). --tests refuses to
// run if check-client says a client is up, rather than doing it anyway.
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const run = (file, args = []) => {
  try {
    return { ok: true, out: execFileSync('node', [path.join(ROOT, 'tools', file), ...args],
      { encoding: 'utf8', cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'] }) };
  }
  catch (e) {
    return { ok: false, out: (e.stdout || '') + (e.stderr || '') };
  }
};

// Each entry: the tool, and how to read a NUMBER of problems out of its
// output. Deliberately explicit — a check that cannot say how many things
// are wrong cannot be trusted to say nothing is.
const CHECKS = [
  // "1 of 1028 annotation items unresolvable" — the count is the FIRST
  // number, and a pattern that simply looked for digits before the keyword
  // read 1028 and then found no match at all, so this check reported "ok"
  // over a deliberately broken item name. Verified by injecting one.
  ['goals + item names', 'audit-goals.mjs', [],
    (o) => sum(o, /^(\d+) of \d+ [^\n]*unresolvable/gm)
      + sum(o, /^(\d+) suspicious/gm)
      + sum(o, /^(\d+) unmatchable/gm)
      + sum(o, /^(\d+) mismatched of/gm)],
  ['place aliases', 'audit-place-spans.mjs', [],
    (o) => sum(o, /(\d+)\s+redundant alias/g) + sum(o, /(\d+)\s+suppression site/g)],
  ['errand chains', 'audit-errand-chains.mjs', [],
    (o) => (o.match(/^\s{2}\w{10}(:\d+)?\s/gm) || []).length],
  ['teleport items', 'audit-teleport-items.mjs', [],
    (o) => Number((o.match(/;\s*(\d+)\s+do not list it/) || [, 0])[1])],
  ['hand-tick steps', 'audit-manual-steps.mjs', [],
    () => 0],  // informational: a count, never a failure
];

let problems = 0;
let broken = 0;
console.log('IRONSCAPE full check\n');
for (const [name, file, args, count] of CHECKS) {
  const res = run(file, args);
  if (!res.ok) {
    broken++;
    console.log(`  BROKEN   ${name.padEnd(20)} ${file} did not run`);
    console.log(res.out.split('\n').filter(Boolean).slice(-3).map((l) => `           ${l}`).join('\n'));
    continue;
  }
  const n = count(res.out);
  problems += n;
  console.log(`  ${(n ? 'FINDINGS' : 'ok').padEnd(8)} ${name.padEnd(20)} ${n ? n + ' to look at' : ''}`);
}

// The route summary is context, not a pass/fail: 83 hand-tick steps is a
// measured property of the guide, not a regression.
const pre = run('preflight.mjs', ['--all']);
if (pre.ok) {
  console.log('');
  for (const line of pre.out.split('\n')) {
    if (/can only be ticked|nowhere to route|no bank stop|Quest Helper/.test(line)) {
      console.log(`  route   ${line.trim()}`);
    }
  }
}

if (process.argv.includes('--tests')) {
  console.log('');
  const client = run('check-client.mjs');
  if (!client.ok) {
    // NOT a silent skip. A skipped test that reads as a pass is the exact
    // failure this whole tool exists to stop.
    broken++;
    console.log('  BROKEN   unit tests          skipped — a client is running, gradle would');
    console.log('           rewrite the classes it is running from. Close it and re-run.');
  }
  else {
    try {
      // gradlew is the Unix script and gradlew.bat the Windows one. The
      // first cut ran the wrong one here and reported "unit tests FAILED"
      // when gradle had never started — a false alarm is not a safe
      // failure, it is how a check gets ignored.
      const windows = process.platform === 'win32';
      const wrapper = windows ? 'gradlew.bat' : 'gradlew';
      // Quoted because shell:true re-parses the command line and this
      // project lives in a path with a space in it ("IRONMAN Guide").
      // Built as ONE string with no separate args array: Node deprecates
      // passing args alongside shell:true, because it concatenates them
      // unescaped. Doing the quoting here makes it explicit.
      const command = `"${path.join(ROOT, wrapper)}" test --console=plain -q`;
      execFileSync(command, [],
        // shell:true on Windows because Node refuses to spawn a .bat
        // directly (EINVAL) — which this reported as "unit tests FAILED"
        // until the error text was printed and said so.
        { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'], shell: windows });
      console.log('  ok       unit tests');
    }
    catch (e) {
      broken++;
      console.log('  FAILED   unit tests');
      const out = (e.stdout || '') + (e.stderr || '');
      const named = out.split('\n').filter((l) => /FAILED|error:/.test(l)).slice(0, 6);
      // ...and when nothing matches, print SOMETHING. The first version
      // printed an empty block, which said "tests failed" and gave no way
      // to find out why — precisely when the reason is unusual.
      console.log((named.length ? named
        : [e.message || 'no output captured'].concat(out.split('\n').filter(Boolean).slice(-4)))
        .map((l) => `           ${l}`).join('\n'));
    }
  }
}
else {
  console.log('\n  (unit tests not run — pass --tests, with the client closed)');
}

console.log('');
if (broken) {
  console.log(`VERDICT: ${broken} check(s) could not answer. Fix those before trusting the rest.`);
  process.exit(1);
}
console.log(problems
  ? `VERDICT: everything ran; ${problems} finding(s) to read before shipping.`
  : 'VERDICT: clean.');

function sum(out, re) {
  let total = 0;
  let m;
  while ((m = re.exec(out)) !== null) {
    total += Number(m[1]);
  }
  return total;
}
