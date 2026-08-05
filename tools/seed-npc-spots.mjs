#!/usr/bin/env node
// Seeds ⌖ targets for GRIND-AT-NPC steps: "Train 42 magic at Moss giants
// near fishing guild" routed to the Fishing Guild (the step's 📍 tag) —
// the guild GATE, not the giants. The wiki's NPC pages carry {{LocLine}}
// spawn clusters with exact pins; the cluster nearest the step's tagged
// place is where the grind actually happens.
//
//   1. Finds steps with a kill/train verb, an NPC phrase, and a resolvable
//      place — but no ⌖ target yet.
//   2. Fetches the NPC's wiki page, parses every {{LocLine}} pin cluster
//      (surface clusters only — dungeon pins can't be routed to directly),
//      and picks the cluster centroid nearest the place (within 100 tiles;
//      farther means the guide means some OTHER cluster — skip, review).
//   3. Writes tools/npc-spots-draft.json — REVIEW, then --apply merges
//      rows marked ok into annotations_oziris.json as step targets.
//
// Usage: node tools/seed-npc-spots.mjs            (build the draft)
//        node tools/seed-npc-spots.mjs --apply    (merge rows marked ok)

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const ANNOTATIONS_FILE = path.join(RES, 'annotations/annotations_oziris.json');
const DRAFT_FILE = path.join(__dirname, 'npc-spots-draft.json');
const CACHE_DIR = path.join(__dirname, '.wiki-cache');

const USER_AGENT = 'ironscape-runelite-plugin dev tooling (npc-spot seeding)';
const REQUEST_DELAY_MS = 700;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const stepId = (plainText) => crypto.createHash('sha256')
  .update(plainText.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8')
  .digest('hex')
  .slice(0, 10);

const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));
const places = JSON.parse(fs.readFileSync(path.join(RES, 'places/places.json'), 'utf8')).places;

const placeCoords = (name) => {
  if (!name) return null;
  const p = places[name.toLowerCase().replace(/’/g, "'").trim()];
  return p ? { x: p.x, y: p.y } : null;
};

// "Train 42 magic at Moss giants near fishing guild" -> npc "moss giant",
// place "fishing guild". The NPC phrase sits between a grind verb/`at` and
// a nearness word; the place runs to the end of the clause.
const GRIND = /\b(?:kill(?:ing)?|slay(?:ing)?|safespot(?:ting)?|at|on)\s+((?:[a-z']+\s){0,2}[a-z']+?)e?s?\s+(?:near|beside|by|outside|west of|east of|north of|south of)\s+(?:the\s+)?([a-z' ]+?)(?:\s*[,.(]|$)/i;
const VERB = /\b(?:kill|killing|slay|slaying|safespot|train(?:ing)?)\b/i;

const rows = [];
for (const ch of guide.chapters) {
  for (const sec of ch.sections) {
    for (const step of sec.steps) {
      const text = (step.content || []).map((c) => c.text).join('');
      if (!VERB.test(text)) continue;
      const id = stepId(text);
      if (annotations.annotations[id]?.target) continue;
      const m = text.match(GRIND);
      if (!m) continue;
      const place = m[2].trim();
      const at = placeCoords(place) || placeCoords(step.metadata?.location);
      if (!at) continue;
      rows.push({
        stepId: id, text: text.slice(0, 90),
        npc: m[1].trim(), place, placeAt: at,
      });
    }
  }
}

async function fetchCached(url) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const cacheFile = path.join(CACHE_DIR, url.replace(/[^a-z0-9.]+/gi, '_'));
  if (fs.existsSync(cacheFile)) {
    return fs.readFileSync(cacheFile, 'utf8');
  }
  await sleep(REQUEST_DELAY_MS);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) return null;
  const text = await res.text();
  fs.writeFileSync(cacheFile, text);
  return text;
}

// action=raw serves "#REDIRECT [[X]]" pages verbatim — chase them (once).
async function rawPage(title, hop = 0) {
  const text = await fetchCached('https://oldschool.runescape.wiki/w/'
    + encodeURIComponent(title.replace(/ /g, '_')) + '?action=raw');
  const redirect = text?.match(/^#REDIRECT\s*\[\[([^\]|#]+)/i);
  return redirect && hop < 2 ? rawPage(redirect[1], hop + 1) : text;
}

// Every {{LocLine}} SURFACE pin cluster on the page: its label and
// centroid. Dungeon clusters (any pin y>=8000, or an explicit mapID)
// are dropped — a route target inside a dungeon strands Shortest Path.
function surfaceClusters(wikitext) {
  const clusters = [];
  for (const m of wikitext.matchAll(/\{\{LocLine([^{}]*)\}\}/g)) {
    const body = m[1];
    if (/\|\s*mapID\s*=/.test(body)) continue;
    const pins = [...body.matchAll(/x:(\d{3,5}),y:(\d{3,5})/g)]
      .map((p) => ({ x: +p[1], y: +p[2] }));
    if (pins.length === 0 || pins.some((p) => p.y >= 8000)) continue;
    const cx = Math.round(pins.reduce((s, p) => s + p.x, 0) / pins.length);
    const cy = Math.round(pins.reduce((s, p) => s + p.y, 0) / pins.length);
    const label = body.match(/\|\s*location\s*=\s*([^\n|]+)/)?.[1]
      ?.replace(/\[\[|\]\]/g, '').trim() || '?';
    clusters.push({ label, x: cx, y: cy });
  }
  return clusters;
}

if (process.argv.includes('--apply')) {
  const draft = JSON.parse(fs.readFileSync(DRAFT_FILE, 'utf8'));
  let applied = 0;
  for (const row of draft) {
    if (!row.ok || !row.coords) continue;
    if (annotations.annotations[row.stepId]?.target) continue;
    annotations.annotations[row.stepId] = {
      ...(annotations.annotations[row.stepId] || {}),
      target: { x: row.coords.x, y: row.coords.y, plane: 0 },
    };
    applied++;
  }
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
  console.log(`applied ${applied} npc-spot target(s) to annotations_oziris.json`);
} else {
  console.log(`${rows.length} grind step(s) with an NPC phrase and no target yet`);
  const draft = [];
  for (const row of rows) {
    // singular page title: "moss giants" -> "Moss giant"
    const name = row.npc.replace(/e?s$/, '');
    const page = name[0].toUpperCase() + name.slice(1);
    const wikitext = await rawPage(page);
    const clusters = wikitext ? surfaceClusters(wikitext) : [];
    let best = null;
    for (const cluster of clusters) {
      const d = Math.max(Math.abs(cluster.x - row.placeAt.x), Math.abs(cluster.y - row.placeAt.y));
      if (d <= 100 && (!best || d < best.d)) {
        best = { ...cluster, d };
      }
    }
    draft.push({
      ...row, page: wikitext ? page : null,
      cluster: best?.label || null,
      coords: best ? { x: best.x, y: best.y } : null,
      ok: false,
    });
    console.log(`${best ? 'HIT ' : 'miss'} ${row.stepId} "${row.npc}" near "${row.place}"`
      + (best ? ` -> ${best.label} @ ${best.x},${best.y} (${best.d} tiles)` : wikitext ? ' (no surface cluster within 100 tiles)' : ' (no wiki page)'));
  }
  fs.writeFileSync(DRAFT_FILE, JSON.stringify(draft, null, 1) + '\n');
  console.log(`wrote ${DRAFT_FILE} — review, set "ok": true on good rows, rerun with --apply`);
}
