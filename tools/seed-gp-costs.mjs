#!/usr/bin/env node
// Seeds GP-COST badges: a buy step that spends money gets a
// {"name": "coins", "quantity": <cost>} annotation item — the existing
// badge machinery then shows a coin sprite with a live have/need in the
// panel AND the bank filter, and the arrival gate deliberately ignores
// coins (money spent mid-step must not wedge completion).
//
// Cost = Σ (wiki item |value| × quantity) over the step's detected buy
// goals. A shop's base sell price equals the item's value at nominal
// stock, so this is the right ballpark (specialty shops drift a few
// percent with stock) — the badge answers "am I carrying enough?", not
// "to the coin". Charter fares and other non-shop fees aren't derivable
// this way: hand-add a coins ItemNeed to those steps.
//
//   gradlew test --tests "*.GoalAuditDumpTest"   (fresh goal dump first)
//   node tools/seed-gp-costs.mjs                 (build the draft)
//   node tools/seed-gp-costs.mjs --apply         (merge rows marked ok)

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');
const ANNOTATIONS_FILE = path.join(RES, 'annotations/annotations_oziris.json');
const DRAFT_FILE = path.join(__dirname, 'gp-costs-draft.json');
const CACHE_DIR = path.join(__dirname, '.wiki-cache');

const REQUEST_DELAY_MS = 700;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));

async function fetchCached(url) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const cacheFile = path.join(CACHE_DIR, url.replace(/[^a-z0-9.]+/gi, '_'));
  if (fs.existsSync(cacheFile)) {
    return fs.readFileSync(cacheFile, 'utf8');
  }
  await sleep(REQUEST_DELAY_MS);
  const res = await fetch(url, {
    headers: { 'user-agent': 'ironscape-optimal gp-cost seeding' } });
  if (!res.ok) return null;
  const text = await res.text();
  fs.writeFileSync(cacheFile, text);
  return text;
}

async function rawPage(title, hop = 0) {
  const text = await fetchCached('https://oldschool.runescape.wiki/w/'
    + encodeURIComponent(title.replace(/ /g, '_')) + '?action=raw');
  const redirect = text?.match(/^#REDIRECT\s*\[\[([^\]|#]+)/i);
  return redirect && hop < 2 ? rawPage(redirect[1], hop + 1) : text;
}

/** The item's infobox |value= (base shop price), or null. */
async function itemValue(name) {
  const page = name[0].toUpperCase() + name.slice(1);
  const wikitext = await rawPage(page);
  const value = wikitext?.match(/\|\s*value\s*=\s*(\d+)/);
  return value ? parseInt(value[1], 10) : null;
}

if (process.argv.includes('--apply')) {
  const draft = JSON.parse(fs.readFileSync(DRAFT_FILE, 'utf8'));
  let applied = 0;
  for (const row of draft) {
    if (!row.ok || !row.cost) continue;
    const entry = annotations.annotations[row.stepId]
      || (annotations.annotations[row.stepId] = {});
    entry.items = entry.items || [];
    if (entry.items.some((i) => /^(coins|gp)$/i.test(i.name))) continue;
    entry.items.push({ name: 'coins', quantity: row.cost });
    applied++;
  }
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
  console.log(`applied ${applied} gp-cost badge(s) to annotations_oziris.json`);
} else {
  // Per-step buy goals from the dump: subId "stepid:N" -> step id.
  const tsv = path.join(ROOT, 'build/goal-audit.tsv');
  if (!fs.existsSync(tsv)) {
    console.error('build/goal-audit.tsv missing — run: gradlew test --tests "*.GoalAuditDumpTest"');
    process.exit(1);
  }
  const goalsByStep = new Map();
  for (const line of fs.readFileSync(tsv, 'utf8').split('\n')) {
    if (!line.startsWith('ITEM\t')) continue;
    const [, subId, qty, name, , text] = line.split('\t');
    if (!/\b(?:buy|purchase)\b/i.test(text)) continue;
    const stepId = subId.split(':')[0];
    if (!goalsByStep.has(stepId)) goalsByStep.set(stepId, []);
    goalsByStep.get(stepId).push({ name, qty: parseInt(qty, 10), text });
  }
  const draft = [];
  for (const [stepId, goals] of goalsByStep) {
    let cost = 0;
    const parts = [];
    let incomplete = false;
    for (const goal of goals) {
      if (/^(coins|gp|gold|cash|money)$/i.test(goal.name)) continue;
      const value = await itemValue(goal.name);
      if (value == null) {
        incomplete = true;
        parts.push(`${goal.name}: ?`);
        continue;
      }
      cost += value * goal.qty;
      parts.push(`${goal.qty}x ${goal.name}@${value}`);
    }
    if (cost <= 0) continue;
    draft.push({
      stepId, text: goals[0].text.slice(0, 90),
      breakdown: parts.join(', '), cost, incomplete,
      // pre-approve complete, meaningful costs; review the rest
      ok: !incomplete && cost >= 100,
    });
    console.log(`${incomplete ? 'PART' : 'ok  '} ${stepId} ${cost}gp  (${parts.join(', ').slice(0, 90)})`);
  }
  fs.writeFileSync(DRAFT_FILE, JSON.stringify(draft, null, 1) + '\n');
  console.log(`wrote ${DRAFT_FILE} — review (ok pre-set on complete rows >=100gp), rerun with --apply`);
}
