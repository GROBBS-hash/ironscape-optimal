#!/usr/bin/env node
// Session-log miner: every dev-client play session writes a full log
// (the `gradlew run` output). This distills it into a bug report —
// warnings, exceptions, IRONSCAPE chat lines and plugin diagnostics —
// so a session surfaces problems even when nobody reported them.
//
//   node tools/mine-session-log.mjs [logfile]
//
// Without an argument it mines the NEWEST *.output under the local
// Claude task directories (where the dev workflow's client logs land).

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function newestTaskLog() {
  const base = path.join(os.homedir(), 'AppData', 'Local', 'Temp', 'claude');
  let newest = null;
  const walk = (dir, depth) => {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const p = path.join(dir, e.name);
      if (e.isDirectory() && depth < 4) {
        walk(p, depth + 1);
      } else if (e.isFile() && e.name.endsWith('.output')) {
        const mtime = fs.statSync(p).mtimeMs;
        if (!newest || mtime > newest.mtime) newest = { path: p, mtime };
      }
    }
  };
  walk(base, 0);
  return newest?.path;
}

const file = process.argv[2] ?? newestTaskLog();
if (!file || !fs.existsSync(file)) {
  console.error('No log file found — pass a path: node tools/mine-session-log.mjs <file>');
  process.exit(1);
}
console.log(`=== mining ${file} ===\n`);
const lines = fs.readFileSync(file, 'utf8').split('\n');

const bump = (map, key) => map.set(key, (map.get(key) ?? 0) + 1);
const warnings = new Map();   // "LEVEL logger - message" -> count
const chat = new Map();       // IRONSCAPE chat/console lines -> count
const probes = new Map();     // our own log.info diagnostics -> count
const exceptions = new Map(); // "Exception: msg @ first frame" -> count

for (let i = 0; i < lines.length; i++) {
  const line = lines[i];
  const level = line.match(/\b(WARN|ERROR)\s+(\S+)\s+-\s+(.*)/);
  if (level) {
    bump(warnings, `${level[1]} ${level[2]} - ${level[3].slice(0, 120)}`);
    continue;
  }
  const ironscape = line.match(/IRONSCAPE:\s*(.*)/);
  if (ironscape) {
    bump(chat, ironscape[1].slice(0, 120));
    continue;
  }
  const ours = line.match(/INFO\s+com\.ironscape\.\S+\s+-\s+(.*)/);
  if (ours) {
    bump(probes, ours[1].slice(0, 120));
    continue;
  }
  const thrown = line.match(/^([\w.]*(?:Exception|Error))(?::\s*(.*))?$/)
    ?? line.match(/\b([\w.]+(?:Exception|Error)):\s+(.*)/);
  if (thrown && !line.includes('at ')) {
    const frame = (lines[i + 1] ?? '').trim().startsWith('at ')
      ? lines[i + 1].trim().slice(0, 100) : '';
    bump(exceptions, `${thrown[1]}: ${(thrown[2] ?? '').slice(0, 100)} ${frame}`);
  }
}

function section(title, map) {
  if (!map.size) return;
  console.log(`--- ${title} ---`);
  for (const [key, count] of [...map.entries()].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${String(count).padStart(4)}x  ${key}`);
  }
  console.log('');
}

section('WARN/ERROR', warnings);
section('exceptions', exceptions);
section('plugin diagnostics (com.ironscape INFO)', probes);
section('IRONSCAPE chat lines', chat);
if (!warnings.size && !exceptions.size) {
  console.log('No warnings or exceptions — clean session.');
}
