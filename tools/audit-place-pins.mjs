#!/usr/bin/env node
// Place-pin cross-check: does each seeded pin actually sit where the wiki
// says that place is?
//
// WHY THIS EXISTS. Goblin Village was pinned at 3525,2975 — in the
// Kharidian Desert, 547 tiles from itself. Nothing caught it for months,
// and nothing could have: the pin is well-formed, on the surface, inside
// a reachable region, and its name is unique, so audit-pin-reachability,
// audit-place-spans and the nav audit all pass it. The only thing wrong
// with it is that it is somewhere else.
//
// The cause was a PARSER bug, so it is a class rather than a one-off.
// A wiki {{Map}} can describe an AREA instead of a point:
//
//   {{Map|name=Goblin Village|mtype=polygon|2941:3525,2975:3525,...}}
//
// seed-places read the first "N,N" in the template body, which on a
// polygon straddles two vertices and yields (y1, x2) — a TRANSPOSED
// coordinate. That is now fixed at source (centroid), but every pin
// seeded before the fix is still in the file, so they have to be found.
//
// Verdicts:
//   TRANSPOSED — swapping our x and y lands on the wiki's location. This
//                is the polygon bug. Re-seed or hand-fix; highest value.
//   DRIFT n    — nearest wiki map pin is n tiles away.
//   OK         — within tolerance of a wiki map pin.
//   NO MAP     — page exists but carries no map template; can't judge.
//   NO PAGE    — nothing at that title; the pin may be hand-captured.
//
// DRIFT IS NOT AUTOMATICALLY A DEFECT, and this tool deliberately does
// not apply anything. Several pins differ from the wiki ON PURPOSE:
// cave and dungeon places are anchored at their SURFACE ENTRANCE because
// Shortest Path cannot draw a route into an interior (the ZMI rule); the
// gnome banks sit at plane 1 because the location page's pin is the
// ground-floor door; a charter/boat pin marks the BOARDING dock, not the
// destination. Read the list, don't sweep it.
//
// By default only places the GUIDE actually mentions are checked — a
// wrong pin costs a play session only if a step can route to it. --all
// checks every entry.
//
// Usage: node tools/audit-place-pins.mjs [--all] [--limit N]

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const CACHE_DIR = path.join(ROOT, 'tools', '.wiki-cache');
const PLACES_FILE = path.join(
  ROOT, 'src/main/resources/com/ironscape/places/places.json');
const GUIDE_FILE = path.join(
  ROOT, 'src/main/resources/com/ironscape/guide/guide_data_oziris.json');

const ALL = process.argv.includes('--all');
const limitArg = process.argv.indexOf('--limit');
const LIMIT = limitArg > -1 ? parseInt(process.argv[limitArg + 1], 10) : Infinity;

// Within this, our pin and the wiki's agree well enough to be the same
// place. Generous on purpose: a {{Map}} pin is a marker someone dropped
// on a building, and ours is often a specific door or booth inside it.
const TOLERANCE = 30;
const REQUEST_DELAY_MS = 120;

const places = JSON.parse(fs.readFileSync(PLACES_FILE, 'utf8')).places;
const guide = JSON.parse(fs.readFileSync(GUIDE_FILE, 'utf8'));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function fetchCached(url) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const cacheFile = path.join(CACHE_DIR, url.replace(/[^a-z0-9.]+/gi, '_'));
  if (fs.existsSync(cacheFile)) {
    return fs.readFileSync(cacheFile, 'utf8');
  }
  await sleep(REQUEST_DELAY_MS);
  const res = await fetch(url, {
    headers: { 'user-agent': 'ironscape-optimal audit tool' },
  });
  if (!res.ok) {
    return null; // not cached: a rename may fix it later
  }
  const text = await res.text();
  fs.writeFileSync(cacheFile, text);
  return text;
}

// action=raw serves "#REDIRECT [[X]]" verbatim — chase it (wave 6's gotcha).
async function rawPage(title, hop = 0) {
  const text = await fetchCached('https://oldschool.runescape.wiki/w/'
    + encodeURIComponent(title.replace(/ /g, '_')) + '?action=raw');
  const redirect = text?.match(/^#REDIRECT\s*\[\[([^\]]+)\]\]/i);
  if (redirect && hop < 2) {
    return rawPage(redirect[1], hop + 1);
  }
  return text;
}

/** Every coordinate a page's map templates describe. */
function mapPoints(wikitext) {
  const points = [];
  const templates = [...wikitext.matchAll(
    /\{\{(?:NPC[ _]map|Object[ _]map|Map)\s*\|([^{}]*)\}\}/gi)];
  for (const [, body] of templates) {
    // LABELLED pin lists — "x:3246,y:3404|x:3248,y:3404|..." — are the
    // wiki's most common form and match none of the branches below (the
    // named branch wants "x=", the positional one wants digits on both
    // sides of the comma). Missing them reads as "this page has no map",
    // which is exactly how the Anvil page's 61 templates looked like an
    // absence of wiki data rather than a parser gap.
    const labelled = [...body.matchAll(
      /x\s*[=:]\s*(\d{3,5})\s*[,|]\s*y\s*[=:]\s*(\d{3,5})/gi)];
    if (labelled.length) {
      for (const m of labelled) {
        points.push({ x: parseInt(m[1], 10), y: parseInt(m[2], 10), area: false });
      }
      continue;
    }
    // Area form next: its vertices also contain "N,N" substrings that
    // the point form would happily misread — the original bug.
    const vertices = [...body.matchAll(/(\d{3,5}):(\d{3,5})/g)]
      .map((m) => [parseInt(m[1], 10), parseInt(m[2], 10)]);
    if (vertices.length >= 2) {
      points.push({
        x: Math.round(vertices.reduce((s, v) => s + v[0], 0) / vertices.length),
        y: Math.round(vertices.reduce((s, v) => s + v[1], 0) / vertices.length),
        area: true,
      });
      continue;
    }
    const xm = body.match(/(?:^|\|)\s*x\s*=\s*(\d{3,5})/i);
    const ym = body.match(/(?:^|\|)\s*y\s*=\s*(\d{3,5})/i);
    if (xm && ym) {
      points.push({ x: parseInt(xm[1], 10), y: parseInt(ym[1], 10), area: false });
      continue;
    }
    // Positional pairs: {{Map|name=..|3208,3226|3213,3226}} — every pair.
    for (const pair of body.matchAll(/(\d{3,5}),\s*(\d{3,5})/g)) {
      points.push({ x: parseInt(pair[1], 10), y: parseInt(pair[2], 10), area: false });
    }
  }
  return points;
}

const dist = (a, b) => Math.round(Math.hypot(a.x - b.x, a.y - b.y));

/** Nearest of the page's points to ours, or null. */
function nearest(pin, points) {
  let best = null;
  for (const point of points) {
    const d = dist(pin, point);
    if (!best || d < best.d) {
      best = { point, d };
    }
  }
  return best;
}

// --- which places the guide can actually route to -------------------------

const stepTexts = [];
for (const chapter of guide.chapters) {
  for (const section of chapter.sections) {
    for (const step of section.steps) {
      const text = (step.content || []).map((c) => c.text).join(' ');
      stepTexts.push(text.toLowerCase());
      const location = step.metadata && step.metadata.location;
      if (location) {
        stepTexts.push(location.toLowerCase());
      }
    }
  }
}
const haystack = stepTexts.join('\n');

function mentioned(key, place) {
  const names = [key, (place.display || '').toLowerCase()];
  return names.some((n) => n
    && new RegExp(`(^|[^a-z])${n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([^a-z]|$)`)
      .test(haystack));
}

// --- run ------------------------------------------------------------------

// Quest names in the place namespace belong to audit-quest-start-pins,
// which knows that Quest Helper's first step is often an approach rather
// than the giver. Judging them against a location page re-reports all of
// that as drift.
//
// The `type` field is how they are normally recognised — but four entries
// are missing it (desert treasure, desert treasure ii, garden of
// tranquility, and dragon slayer, which is not in quest_givers.json
// either), so recognise the NAME as well. Deliberately not "fixed" by
// setting the type: firstPlaceIn excludes type=quest, and for "Do Desert
// Treasure" (location tag "Various") that untyped entry is currently the
// step's ONLY routing target. Typing it would trade a small arrival risk
// for two steps that can route nowhere.
const questNames = new Set();
const questNamesFile = path.join(ROOT, 'build', 'quest-names.tsv');
if (fs.existsSync(questNamesFile)) {
  for (const line of fs.readFileSync(questNamesFile, 'utf8').split('\n')) {
    const name = line.split('\t')[2];
    if (name) {
      questNames.add(name.trim().toLowerCase());
    }
  }
}

const reviewed = JSON.parse(fs.readFileSync(
  path.join(ROOT, 'tools', 'place-pins-reviewed.json'), 'utf8')).reviewed;

let questSkipped = 0;
const entries = Object.entries(places)
  .filter(([key, place]) => {
    if (place.type === 'quest' || place.type === 'transport') {
      return false;
    }
    if (questNames.has((place.display || key).toLowerCase())) {
      questSkipped++;
      return false;
    }
    return true;
  })
  .filter(([key, place]) => ALL || mentioned(key, place))
  .slice(0, LIMIT);

console.log(`Checking ${entries.length} place pins`
  + `${ALL ? '' : ' the guide mentions'} against the wiki.\n`);

const findings = [];
let ok = 0;

for (const [key, place] of entries) {
  const wikitext = await rawPage(place.display || key);
  if (!wikitext) {
    findings.push({ rank: 1, key, place, verdict: 'NO PAGE', detail: '' });
    continue;
  }
  const points = mapPoints(wikitext);
  if (!points.length) {
    findings.push({ rank: 1, key, place, verdict: 'NO MAP', detail: '' });
    continue;
  }
  const best = nearest(place, points);
  if (best.d <= TOLERANCE) {
    ok++;
    continue;
  }
  // The signature of the polygon bug: we are the wiki's location with x
  // and y swapped. Checked BEFORE reporting plain drift, because the fix
  // is different (re-seed) and the confidence is total.
  const swapped = nearest({ x: place.y, y: place.x }, points);
  if (swapped.d <= TOLERANCE) {
    findings.push({
      rank: 3,
      key,
      place,
      verdict: 'TRANSPOSED',
      detail: `${place.x},${place.y} -> should be ~${swapped.point.x},${swapped.point.y}`,
    });
    continue;
  }
  if (reviewed[key]) {
    ok++;
    continue; // a recorded decision, not a finding
  }
  findings.push({
    rank: 2,
    key,
    place,
    verdict: `DRIFT ${best.d}`,
    detail: `${place.x},${place.y} vs wiki ${best.point.x},${best.point.y}`
      + (best.point.area ? ' (area centre)' : ''),
    distance: best.d,
  });
}

findings.sort((a, b) => b.rank - a.rank || (b.distance || 0) - (a.distance || 0));

for (const f of findings) {
  console.log(`${f.verdict.padEnd(12)} ${f.key}`);
  if (f.detail) {
    console.log(`             ${f.detail}`);
  }
}

const counts = findings.reduce((acc, f) => {
  // "NO MAP"/"NO PAGE" are two words — splitting on the space reported
  // both as "NO" and made the summary lie about which one it found.
  const kind = f.verdict.startsWith('DRIFT') ? 'DRIFT' : f.verdict;
  acc[kind] = (acc[kind] || 0) + 1;
  return acc;
}, {});
console.log(`\n${ok} OK, ` + Object.entries(counts)
  .map(([k, n]) => `${n} ${k}`).join(', '));
console.log(`(${questSkipped} quest-name pins skipped - audit-quest-start-pins owns those;`
  + ` ${Object.keys(reviewed).length} reviewed exemptions applied.)`);
console.log('TRANSPOSED is the polygon-parse bug and is always wrong.');
console.log('DRIFT needs reading: entrance anchors and boarding docks differ on purpose.');
