#!/usr/bin/env node
// Seeds FACILITY TARGETS: steps like "make molten glass at edgeville
// furnace" or "make unfired bowl at barb village" name a town + a
// facility (furnace, anvil, spinning wheel, range, altar...). The wiki's
// facility pages carry a map pin for every instance in the game; the
// pin NEAREST the step's town (whose coordinates places.json already
// knows) is that step's target. Proximity is the review: a pin more
// than MAX_TOWN_DISTANCE tiles from the town is rejected.
//
// Usage: node tools/seed-facilities.mjs [--dry-run]
// Applies directly (unlike seed-shops) because the town-proximity rule
// leaves no judgement call; existing targets are never overwritten.

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GUIDE_FILE = path.join(__dirname, '../src/main/resources/com/ironscape/guide/guide_data_oziris.json');
const ANNOTATIONS_FILE = path.join(__dirname, '../src/main/resources/com/ironscape/annotations/annotations_oziris.json');
const PLACES_FILE = path.join(__dirname, '../src/main/resources/com/ironscape/places/places.json');

const USER_AGENT = 'ironscape-runelite-plugin dev tooling (facility seeding)';
const REQUEST_DELAY_MS = 700;
const MAX_TOWN_DISTANCE = 80;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const runText = (runs) => (runs || []).map((r) => r.text).join('');

const FACILITY_PAGES = {
  'furnace': 'Furnace',
  'anvil': 'Anvil',
  'spinning wheel': 'Spinning wheel',
  'range': 'Range (cooking)',
  'altar': 'Altar',
  'pottery wheel': 'Potter\'s wheel',
  'pottery oven': 'Pottery oven',
  'loom': 'Loom',
  'windmill': 'Windmill',
};
const FACILITY_RE = new RegExp('\\b(' + Object.keys(FACILITY_PAGES).join('|') + ')\\b', 'i');

const guide = JSON.parse(fs.readFileSync(GUIDE_FILE, 'utf8'));
const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));
const places = JSON.parse(fs.readFileSync(PLACES_FILE, 'utf8')).places;

const key = (s) => s.toLowerCase().replace(/’/g, "'").replace(/[^a-z0-9' ]/g, ' ').replace(/\s+/g, ' ').trim();

// The town a step means: a known place named in its text, else its 📍 tag.
function townOf(text, location) {
  const lt = ' ' + key(text) + ' ';
  let best = null;
  for (const [name, p] of Object.entries(places)) {
    if (name.length >= 4 && lt.includes(' ' + name + ' ')) {
      // Prefer the LONGEST match ("barbarian village" over "barb")
      if (!best || name.length > best.name.length) best = { name, ...p };
    }
  }
  if (best) return best;
  if (location) {
    const k = key(location.replace(/^(north|south|east|west)([ -](east|west))?\s+of\s+/i, ''));
    if (places[k]) return { name: k, ...places[k] };
  }
  return null;
}

// Collect facility steps that still lack a target.
const wanted = [];
for (const ch of guide.chapters) {
  for (const sec of ch.sections) {
    for (const step of sec.steps) {
      const text = runText(step.content);
      const m = text.match(FACILITY_RE);
      if (!m) continue;
      const id = stepId(text);
      if (annotations.annotations[id]?.target) continue;
      const town = townOf(text, step.metadata?.location);
      if (!town) continue;
      wanted.push({ id, text: text.slice(0, 80), facility: m[1].toLowerCase(), town });
    }
  }
}
console.log(`${wanted.length} facility step(s) without a target`);

// Fetch each needed facility page once and collect its surface pins.
const pinsByFacility = {};
for (const facility of new Set(wanted.map((w) => w.facility))) {
  await sleep(REQUEST_DELAY_MS);
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=1&page='
    + encodeURIComponent(FACILITY_PAGES[facility]);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  const wikitext = (await res.json())?.parse?.wikitext?.['*'] || '';
  const pins = [];
  for (const tpl of wikitext.matchAll(/\{\{(?:Object[ _]map|Map)\s*\|([^{}]*)\}\}/gi)) {
    const body = tpl[1];
    if (/mapID\s*=/i.test(body)) continue; // other-map instances (dungeons)
    let x; let y;
    const xm = body.match(/x\s*[=:]\s*(\d{4})/i);
    const ym = body.match(/y\s*[=:]\s*(\d{4})/i);
    if (xm && ym) { x = +xm[1]; y = +ym[1]; }
    else {
      const pair = body.match(/(\d{4}),\s*(\d{4})/);
      if (!pair) continue;
      x = +pair[1]; y = +pair[2];
    }
    if (y < 8000) pins.push({ x, y });
  }
  pinsByFacility[facility] = pins;
  console.log(`  ${facility}: ${pins.length} surface pin(s)`);
}

let applied = 0;
for (const w of wanted) {
  const pins = pinsByFacility[w.facility] || [];
  let best = null;
  for (const pin of pins) {
    const d = Math.max(Math.abs(pin.x - w.town.x), Math.abs(pin.y - w.town.y));
    if (d <= MAX_TOWN_DISTANCE && (!best || d < best.d)) best = { ...pin, d };
  }
  if (!best) {
    console.log(`miss ${w.id} ${w.facility} @ ${w.town.name} | ${w.text}`);
    continue;
  }
  console.log(`HIT  ${w.id} ${w.facility} @ ${w.town.name} -> ${best.x},${best.y} (${best.d} tiles) | ${w.text}`);
  if (!process.argv.includes('--dry-run')) {
    annotations.annotations[w.id] = {
      ...(annotations.annotations[w.id] || {}),
      target: { x: best.x, y: best.y, plane: 0 },
    };
    applied++;
  }
}
if (applied > 0) {
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
}
console.log(`applied ${applied} facility target(s)`);
