#!/usr/bin/env node
// Annotation authoring aid: pull verified WorldPoints and item
// requirements out of Quest Helper's open-source quest files (BSD-2,
// github.com/Zoinkwiz/quest-helper) — the fastest way to author errand
// chains, item sources and ⌖ targets without manual wiki digging.
//
//   node tools/qh-lookup.mjs "Waterfall Quest"
//       -> every step with a WorldPoint, plus the quest's ItemRequirements
//
//   node tools/qh-lookup.mjs --item "glarial"
//       -> code-search the whole repo (needs `gh` logged in), then print
//          matching statements from each hit file
//
//   node tools/qh-lookup.mjs --url <raw-github-url>
//       -> same report for an exact file when the name guess misses
//
// Data ONLY — coordinates and item names are game facts; no code is
// copied into the plugin.

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const RAW_BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/';
const SRC_ROOT = 'src/main/java/com/questhelper/helpers/quests/';
const CACHE_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '.qh-cache');

async function fetchCached(url) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const cacheFile = path.join(CACHE_DIR, url.replace(/[^a-z0-9.]+/gi, '_'));
  if (fs.existsSync(cacheFile)) {
    return fs.readFileSync(cacheFile, 'utf8');
  }
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} for ${url}`);
  }
  const text = await res.text();
  fs.writeFileSync(cacheFile, text);
  return text;
}

// "Waterfall Quest" -> quests/waterfallquest/WaterfallQuest.java
function guessPath(questName) {
  const words = questName.split(/[^a-zA-Z0-9]+/).filter(Boolean);
  const cls = words.map(w => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
  const slug = words.join('').toLowerCase();
  return `${SRC_ROOT}${slug}/${cls}.java`;
}

// Split Java into ;-terminated statements and report the interesting ones.
function report(java, label) {
  console.log(`\n=== ${label} ===`);
  const statements = java.split(';');

  const steps = [];
  for (const st of statements) {
    if (!st.includes('new WorldPoint(')) continue;
    const variable = st.match(/(\w+)\s*=\s*new\s+(\w+)\(/);
    const points = [...st.matchAll(/new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)/g)]
      .map(m => `${m[1]},${m[2]},${m[3]}`);
    const description = st.match(/"((?:[^"\\]|\\.)*)"/);
    steps.push({
      variable: variable ? variable[1] : '?',
      type: variable ? variable[2] : '?',
      points,
      description: description ? description[1] : '',
    });
  }
  if (steps.length) {
    console.log('\n--- steps with WorldPoints ---');
    for (const s of steps) {
      console.log(`${s.variable} (${s.type}) @ ${s.points.join(' / ')}`);
      if (s.description) console.log(`    "${s.description}"`);
    }
  }

  const items = new Set();
  for (const m of java.matchAll(/new ItemRequirement\(\s*"((?:[^"\\]|\\.)*)"/g)) {
    items.add(m[1]);
  }
  if (items.size) {
    console.log('\n--- ItemRequirements ---');
    for (const name of items) console.log(`  ${name}`);
  }
  if (!steps.length && !items.size) {
    console.log('(no WorldPoints or ItemRequirements found)');
  }
}

const args = process.argv.slice(2);

if (args[0] === '--item') {
  const term = args[1];
  if (!term) {
    console.error('usage: qh-lookup.mjs --item <search term>');
    process.exit(1);
  }
  // gh handles auth for the code-search API (anonymous search is refused).
  let hits;
  try {
    hits = JSON.parse(execFileSync('gh', ['api', '-X', 'GET', 'search/code',
      '-f', `q="${term}" repo:Zoinkwiz/quest-helper`,
      '--jq', '[.items[].path]'], { encoding: 'utf8' }));
  } catch (e) {
    console.error('gh code search failed (is `gh` installed and logged in?)');
    throw e;
  }
  if (!hits.length) {
    console.log(`No files in quest-helper mention "${term}".`);
    process.exit(0);
  }
  const lower = term.toLowerCase();
  for (const file of hits) {
    const java = await fetchCached(RAW_BASE + file);
    console.log(`\n=== ${file} ===`);
    // Print whole statements (not lines): a WorldPoint two lines above
    // the search term is the same statement and exactly what we want.
    for (const st of java.split(';')) {
      if (st.toLowerCase().includes(lower)) {
        console.log(st.replace(/\s+/g, ' ').trim().slice(0, 300) + ';');
      }
    }
  }
} else if (args[0] === '--url') {
  const java = await fetchCached(args[1]);
  report(java, args[1]);
} else if (args[0]) {
  const guessed = guessPath(args[0]);
  try {
    const java = await fetchCached(RAW_BASE + guessed);
    report(java, guessed);
  } catch (e) {
    console.error(`Could not fetch ${RAW_BASE}${guessed}`);
    console.error('The class-name guess missed — find the file under');
    console.error('https://github.com/Zoinkwiz/quest-helper/tree/master/' + SRC_ROOT);
    console.error('and re-run with --url <raw url>.');
    process.exit(1);
  }
} else {
  console.error('usage: qh-lookup.mjs "<Quest Name>" | --item <term> | --url <raw url>');
  process.exit(1);
}
