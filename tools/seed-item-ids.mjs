#!/usr/bin/env node
// Seeds the bundled item-name -> item-id map from the OSRS Wiki.
//
// The plugin resolves item sprites through RuneLite's price list, which
// only knows TRADEABLE items — quest items (ghostspeak amulet, talismans,
// Glarial's pebble...) get no icon in badges, the bank's ghost sections,
// or over shop NPCs' heads. Wiki item infoboxes carry the item id, so
// this tool looks up every item name the plugin tracks and writes the
// ones it can resolve to item_ids.json, which ItemTracker consults FIRST.
//
// Usage:
//   1. gradlew compileTestJava
//   2. java -cp <classes+gson+api+resources> com.ironscape.guide.PrintItemNamesProbe > tools/item-names.txt
//   3. node tools/seed-item-ids.mjs [--dry-run]
//
// (Or use the one-liner in the README. Existing entries are kept; only
// missing names are looked up.)

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const NAMES_FILE = path.join(__dirname, 'item-names.txt');
const OUT_FILE = path.join(__dirname, '../src/main/resources/com/ironscape/items/item_ids.json');

const USER_AGENT = 'IRONSCAPE-Optimal RuneLite plugin dev tooling';
const REQUEST_DELAY_MS = 700;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const names = fs.readFileSync(NAMES_FILE, 'utf8').split(/\r?\n/)
  .map((n) => n.trim().toLowerCase()).filter(Boolean);

const existing = fs.existsSync(OUT_FILE)
  ? JSON.parse(fs.readFileSync(OUT_FILE, 'utf8'))
  : {};

// Names the guide uses that need normalizing before a wiki lookup —
// mirror of the START of ItemTracker.aliases (keep in sync by hand).
function canonical(name) {
  let key = name;
  if (key.startsWith('noted ')) key = key.slice(6);
  if (['gp', 'gold', 'cash', 'money'].includes(key)) key = 'coins';
  key = key.replace(/^(bronze|iron|steel|mithril|adamant|rune|amethyst) arrowheads?$/, '$1 arrowtips');
  return key;
}

/** Try the name, then its singular, as wiki page titles. */
function pageCandidates(name) {
  const titled = name.charAt(0).toUpperCase() + name.slice(1);
  const candidates = [titled];
  if (titled.endsWith('s')) candidates.push(titled.slice(0, -1));
  return candidates;
}

async function lookupId(page) {
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=1&page='
    + encodeURIComponent(page);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) return null;
  const json = await res.json();
  const wikitext = json?.parse?.wikitext?.['*'];
  if (!wikitext || !/\{\{Infobox Item/i.test(wikitext)) return null;
  // First id in the item infobox; multi-variant pages list id1/id2 —
  // take the plain or first-numbered one.
  const m = wikitext.match(/\|\s*id(?:1)?\s*=\s*(\d+)/);
  return m ? parseInt(m[1], 10) : null;
}

const dryRun = process.argv.includes('--dry-run');
let added = 0;
let skipped = 0;
const misses = [];

for (const raw of names) {
  const name = canonical(raw);
  if (existing[name] !== undefined) {
    skipped++;
    continue;
  }
  if (dryRun) {
    console.log('  would look up:', name);
    continue;
  }
  let id = null;
  for (const page of pageCandidates(name)) {
    await sleep(REQUEST_DELAY_MS);
    try {
      id = await lookupId(page);
    } catch (e) {
      console.warn(`  ! ${page}: ${e.message}`);
    }
    if (id) break;
  }
  if (id) {
    existing[name] = id;
    added++;
    console.log(`  + ${name} -> ${id}`);
  } else {
    misses.push(name);
  }
}

if (!dryRun) {
  const sorted = {};
  for (const key of Object.keys(existing).sort()) sorted[key] = existing[key];
  fs.writeFileSync(OUT_FILE, JSON.stringify(sorted, null, 2) + '\n');
  console.log(`\nDone: ${added} added, ${skipped} already mapped, ${misses.length} not found.`);
  if (misses.length) {
    console.log('No wiki item page found for:');
    misses.forEach((n) => console.log(`  - ${n}`));
  }
  console.log(`Wrote ${OUT_FILE} — rebuild the plugin to bundle it.`);
}
