#!/usr/bin/env node
// Shop-target cross-check: every seeded shop pin came from a wiki search
// ("Ardougne Ardy farming shop" -> best hit -> {{Map}} coords), and the
// best hit is sometimes a TOWN page — its map pin is then some random
// landmark, the ⌖ marker lands 70 tiles from the shop, and the shop-NPC
// anchor crowns whoever happens to stand there (a compost icon over
// Councillor Halgrive's head, owner report 2026-08-05).
//
// This audit re-checks each applied shop target against the wiki:
//
//   1. Is the page it came from actually a SHOP page (has a stock table)?
//   2. Does that shop actually SELL the item the step buys?
//   3. Does the pin in annotations_oziris.json still match the page's
//      own {{Map}} coordinates (drift > 15 tiles flags)?
//
// Verdicts: OK — page is a shop, sells the item, pin matches.
//           TOWN PAGE — no stock table; the pin is a random landmark. Fix.
//           NOT SOLD — shop page, but the item isn't in its stock. Wrong
//                      shop matched (or item phrase misextracted); verify.
//           DRIFT — pin no longer matches the page's coords (hand-edited
//                   pins are fine — check the hand edit was deliberate).
//           NO ITEM — couldn't extract what the step buys; verify by hand.
//
// Usage: node tools/audit-shops.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const CACHE_DIR = path.join(ROOT, 'tools', '.wiki-cache');
const DRAFT_FILE = path.join(ROOT, 'tools', 'shop-targets-draft.json');
const ANNOTATIONS_FILE = path.join(
  ROOT, 'src/main/resources/com/ironscape/annotations/annotations_oziris.json');

const draft = JSON.parse(fs.readFileSync(DRAFT_FILE, 'utf8'));
const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8')).annotations;

async function fetchCached(url) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const cacheFile = path.join(CACHE_DIR, url.replace(/[^a-z0-9.]+/gi, '_'));
  if (fs.existsSync(cacheFile)) {
    return fs.readFileSync(cacheFile, 'utf8');
  }
  const res = await fetch(url, { headers: { 'user-agent': 'ironscape-optimal audit tool' } });
  if (!res.ok) {
    return null; // cache misses aren't cached: a rename may fix them
  }
  const text = await res.text();
  fs.writeFileSync(cacheFile, text);
  return text;
}

// action=raw serves "#REDIRECT [[X]]" pages verbatim — chase them (once).
async function rawPage(title, hop = 0) {
  const text = await fetchCached('https://oldschool.runescape.wiki/w/'
    + encodeURIComponent(title.replace(/ /g, '_')) + '?action=raw');
  const redirect = text?.match(/^#REDIRECT\s*\[\[([^\]]+)\]\]/i);
  if (redirect && hop < 2) {
    return rawPage(redirect[1], hop + 1);
  }
  return text;
}

// "Buy 2 ropes, 5 vials, 30 balls of wool and 7 papyrus from ardy
// general store" -> everything between buy/purchase and the shop
// preposition; counts and filler words wash out in normalization.
const BOUGHT = /\b(?:buy|purchase)\b\s+([^.(]+?)\s+(?:from|at|in)\b/i;

// Word-normalized name match, tolerant both ways: possessives and
// plurals collapse ("wizard mind bombs" = "Wizard's mind bomb") and a
// store name that CONTAINS every wanted word counts ("flour" is sold as
// "Pot of flour", "wine" as "Jug of wine"). One matching part of a
// comma/"and" list confirms the shop — the pin is what's being audited,
// not the full shopping list.
const FILLER = new Set(['a', 'an', 'the', 'some', 'all', 'of', 'x', 'k',
  'inv', 'invs', 'inventory', 'inventories', 'more', 'few']);
const normWords = (s) => s.toLowerCase().replace(/'/g, '')
  .split(/[^a-z]+/).filter((w) => w && !FILLER.has(w)).map((w) => w.replace(/s$/, ''));

function sells(storeNames, itemPhrase) {
  const parts = itemPhrase.split(/,|\band\b/).map((p) => normWords(p)).filter((p) => p.length);
  const stock = storeNames.map((name) => normWords(name));
  return parts.some((want) =>
    stock.some((sold) => want.every((w) => sold.includes(w))));
}

const counts = {};
const verdict = (tag, row, detail = '') => {
  counts[tag] = (counts[tag] || 0) + 1;
  console.log(`${tag.padEnd(9)} ${row.stepId} "${(row.text || '').slice(0, 60)}" -> ${row.page || '-'} ${detail}`);
};

for (const row of draft) {
  const pinned = annotations[row.stepId]?.target;
  if (!pinned || !row.page) {
    continue; // never applied (or superseded by a capture) — nothing to audit
  }
  if (row.page.includes('hand-corrected')) {
    verdict('HAND', row, '(pin was placed by hand — not wiki-audited)');
    continue;
  }
  const wikitext = await rawPage(row.page);
  if (!wikitext) {
    verdict('NO PAGE', row);
    continue;
  }
  const storeNames = [...wikitext.matchAll(/\{\{StoreLine\|name=([^|}]+)/g)].map((m) => m[1].trim());
  if (storeNames.length === 0) {
    verdict('TOWN PAGE', row, '(no stock table — pin is a random landmark)');
    continue;
  }
  const item = row.text?.match(BOUGHT)?.[1]?.trim();
  if (!item) {
    verdict('NO ITEM', row);
    continue;
  }
  if (!sells(storeNames, item)) {
    verdict('NOT SOLD', row, `("${item}" not in: ${storeNames.slice(0, 6).join(', ')}...)`);
    continue;
  }
  const coordsMatch = wikitext.match(/\{\{Map[^}]*?(\d{3,5}),\s*(\d{3,5})/i);
  if (coordsMatch) {
    const dx = Math.abs(parseInt(coordsMatch[1], 10) - pinned.x);
    const dy = Math.abs(parseInt(coordsMatch[2], 10) - pinned.y);
    if (dx > 15 || dy > 15) {
      verdict('DRIFT', row, `(pin ${pinned.x},${pinned.y} vs page ${coordsMatch[1]},${coordsMatch[2]})`);
      continue;
    }
  }
  verdict('OK', row);
}

console.log('\n' + Object.entries(counts).map(([k, v]) => `${k}: ${v}`).join('  |  '));
if ((counts['TOWN PAGE'] || 0) + (counts['NOT SOLD'] || 0) > 0) {
  console.log('TOWN PAGE / NOT SOLD rows need a corrected pin: fix the draft row\'s'
    + ' coords + page, update the annotation (or capture in-game), and re-run.');
}
