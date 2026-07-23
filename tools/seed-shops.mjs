#!/usr/bin/env node
// Seeds SHOP TARGETS for every "buy X from the <shop>" step in the guide.
//
// The repeating play-test complaint: a buy step names its shop generically
// ("the fishing store", "the general store") — no NPC name, no place name
// the text scan can resolve — so the step gets no navigation target, no
// shopkeeper outline, no item-over-head. The wiki knows exactly where
// every shop is; this script asks it once per step and writes DRAFT
// target annotations for review:
//
//   1. Finds every step whose text says buy/purchase and extracts the
//      shop phrase + the step's authored location tag.
//   2. Wiki-searches "<location> <shop phrase>", takes the best hit, and
//      reads the {{Map|x,y|plane}} coordinates off that page (same
//      parsing as seed-places.mjs).
//   3. Writes tools/shop-targets-draft.json — REVIEW IT, then run with
//      --apply to merge the reviewed rows into annotations_oziris.json
//      as step-keyed { target: {x, y, plane} } entries (existing keys,
//      e.g. in-game ⌖ captures, are never overwritten).
//
// Usage: node tools/seed-shops.mjs            (build the draft)
//        node tools/seed-shops.mjs --apply    (merge draft rows marked ok)

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GUIDE_FILE = path.join(__dirname, '../src/main/resources/com/ironscape/guide/guide_data_oziris.json');
const ANNOTATIONS_FILE = path.join(__dirname, '../src/main/resources/com/ironscape/annotations/annotations_oziris.json');
const DRAFT_FILE = path.join(__dirname, 'shop-targets-draft.json');

const USER_AGENT = 'ironscape-runelite-plugin dev tooling (one-off shop seeding)';
const REQUEST_DELAY_MS = 700;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Same id scheme as GuideLoader.stepId: first 10 hex chars of the
// SHA-256 of the step text, whitespace-collapsed and lowercased.
const stepId = (plainText) => crypto.createHash('sha256')
  .update(plainText.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8')
  .digest('hex')
  .slice(0, 10);

const runText = (runs) => (runs || []).map((r) => r.text).join('');

// ---------------------------------------------------------------------
// 1. Collect buy-steps and their shop phrases
// ---------------------------------------------------------------------

const guide = JSON.parse(fs.readFileSync(GUIDE_FILE, 'utf8'));
const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));

const SHOP_PHRASE = /(?:from|at|in)\s+(?:the\s+)?([a-z][a-z' -]*?(?:store|shop|stall|bar|charters?|emporium))\b/i;
const BARE_SHOP = /\b((?:general|fishing|crafting|hunter|clothes|pickaxe|food|farming|fishing)\s+(?:store|shop))\b/i;

const rows = [];
for (const ch of guide.chapters) {
  for (const sec of ch.sections) {
    for (const step of sec.steps) {
      const text = runText(step.content);
      if (!/\b(?:buy|purchase)\b/i.test(text)) continue;
      const id = stepId(text);
      if (annotations.annotations[id]?.target) continue; // already targeted
      const phrase = text.match(SHOP_PHRASE)?.[1] || text.match(BARE_SHOP)?.[1];
      if (!phrase) continue; // named-NPC steps are seed-places' job
      const location = step.metadata?.location || '';
      rows.push({ stepId: id, text: text.slice(0, 90), location, phrase: phrase.trim() });
    }
  }
}

// ---------------------------------------------------------------------
// 2. Wiki lookup (search -> page -> {{Map}} coords)
// ---------------------------------------------------------------------

async function wikiSearch(query) {
  const url = 'https://oldschool.runescape.wiki/api.php?action=query&list=search&format=json'
    + '&srlimit=5&srsearch=' + encodeURIComponent(query);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) return [];
  const json = await res.json();
  return (json?.query?.search || []).map((s) => s.title);
}

async function fetchCoords(page) {
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=1&page='
    + encodeURIComponent(page);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) return null;
  const json = await res.json();
  const wikitext = json?.parse?.wikitext?.['*'];
  if (!wikitext) return null;
  const templates = [...wikitext.matchAll(/\{\{(?:NPC[ _]map|Object[ _]map|Map)\s*\|([^{}]*)\}\}/gi)];
  let fallback = null;
  for (const tpl of templates) {
    const coords = parseMapBody(tpl[1]);
    if (!coords) continue;
    if (coords.y < 8000) return coords;
    fallback = fallback || coords;
  }
  return fallback;
}

function parseMapBody(body) {
  let x; let y;
  const xm = body.match(/(?:^|\|)\s*x\s*=\s*(\d{3,5})/i);
  const ym = body.match(/(?:^|\|)\s*y\s*=\s*(\d{3,5})/i);
  if (xm && ym) {
    x = parseInt(xm[1], 10);
    y = parseInt(ym[1], 10);
  } else {
    const pair = body.match(/(\d{3,5}),\s*(\d{3,5})/);
    if (!pair) return null;
    x = parseInt(pair[1], 10);
    y = parseInt(pair[2], 10);
  }
  const plane = body.match(/plane\s*=\s*(\d)/);
  return { x, y, plane: plane ? parseInt(plane[1], 10) : 0 };
}

// ---------------------------------------------------------------------
// 3. Draft (default) or apply (--apply)
// ---------------------------------------------------------------------

if (process.argv.includes('--apply')) {
  const draft = JSON.parse(fs.readFileSync(DRAFT_FILE, 'utf8'));
  let applied = 0;
  for (const row of draft) {
    if (!row.ok || !row.coords) continue;
    if (annotations.annotations[row.stepId]?.target) continue;
    annotations.annotations[row.stepId] = {
      ...(annotations.annotations[row.stepId] || {}),
      target: row.coords,
    };
    applied++;
  }
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
  console.log(`applied ${applied} shop target(s) to annotations_oziris.json`);
} else {
  console.log(`${rows.length} buy-step(s) with a shop phrase and no target yet`);
  const draft = [];
  for (const row of rows) {
    await sleep(REQUEST_DELAY_MS);
    const query = `${row.location} ${row.phrase}`.trim();
    const hits = await wikiSearch(query);
    let coords = null;
    let page = null;
    for (const title of hits.slice(0, 3)) {
      await sleep(REQUEST_DELAY_MS);
      coords = await fetchCoords(title);
      if (coords) { page = title; break; }
    }
    // ok=false until a human (or a careful reviewer) flips it after
    // checking the page title actually IS that step's shop.
    draft.push({ ...row, query, page, coords, ok: false });
    console.log(`${coords ? 'HIT ' : 'miss'} ${row.stepId} "${row.phrase}" @ ${row.location || '?'} -> ${page || '-'} ${coords ? JSON.stringify(coords) : ''}`);
  }
  fs.writeFileSync(DRAFT_FILE, JSON.stringify(draft, null, 1) + '\n');
  console.log(`wrote ${DRAFT_FILE} — review, set "ok": true on good rows, rerun with --apply`);
}
