#!/usr/bin/env node
// Seeds INGREDIENT LISTS for "make X" steps: the product's wiki page
// carries a {{Recipe}} template with exact materials. Intermediates the
// player never shops for ("Uncooked berry pie", "Pastry dough") are
// expanded recursively, so "Make redberry pie" shows pie dish + pot of
// flour + bucket of water + redberries — each a live have/need badge.
//
// Reads build/goal-audit.tsv (run GoalAuditDumpTest first) to find the
// make-steps and their product goals. Existing annotation item lists
// are MERGED (never overwritten); --dry-run only prints.
//
// Usage: node tools/seed-recipes.mjs [--dry-run]

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const ANNOTATIONS_FILE = path.join(ROOT, 'src/main/resources/com/ironscape/annotations/annotations_oziris.json');
const USER_AGENT = 'ironscape-runelite-plugin dev tooling (recipe seeding)';
const REQUEST_DELAY_MS = 700;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Intermediates to expand into THEIR ingredients instead of listing.
const EXPAND = /^(?:uncooked |unfired |raw |incomplete |chocolatey |part |pie shell$|.*\bdough$|.* \(unf\)$|unstrung )/i;
// Recipe mats that aren't shopping-list material.
const SKIP_MATS = /^(?:water|fire|earth|air) rune$|^knife$|^chisel$|^hammer$|^needle$|^thread$/i;

const tsv = path.join(ROOT, 'build/goal-audit.tsv');
if (!fs.existsSync(tsv)) {
  console.error('run: gradlew test --tests "*.GoalAuditDumpTest" first');
  process.exit(1);
}

// One make-step -> ALL its product goals: "Make 4 red, 4 blue, 5
// yellow dye" needs every recipe, not just the first (first-only left
// the owner ingredient-less on blue and yellow).
const products = new Map(); // subId -> [{name, qty, text}, ...]
for (const line of fs.readFileSync(tsv, 'utf8').split('\n')) {
  if (!line.startsWith('ITEM\t')) continue;
  const [, subId, qty, name, , text] = line.split('\t');
  if (!/\bmake\b/i.test(text)) continue;
  if (!products.has(subId)) products.set(subId, []);
  products.get(subId).push({ name, qty: +qty, text });
}
console.log(`${products.size} make-step(s) found`);

async function fetchRecipe(page) {
  await sleep(REQUEST_DELAY_MS);
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=1&page='
    + encodeURIComponent(page);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) return null;
  const wikitext = (await res.json())?.parse?.wikitext?.['*'] || '';
  const recipe = wikitext.match(/\{\{Recipe\s*\|([\s\S]*?)\n\}\}/i);
  if (!recipe) return null;
  const body = recipe[1];
  const mats = [];
  for (let i = 1; i <= 8; i++) {
    const mat = body.match(new RegExp(`\\|\\s*mat${i}\\s*=\\s*([^|\\n]+)`));
    if (!mat) break;
    const qty = body.match(new RegExp(`\\|\\s*mat${i}(?:quantity|qty)\\s*=\\s*(\\d+)`));
    // "Incomplete stew#Potato" — a wiki section anchor, not part of the name.
    mats.push({ name: mat[1].trim().replace(/#.*$/, ''), quantity: qty ? +qty[1] : 1 });
  }
  return mats.length ? mats : null;
}

/** Recipe mats expanded down to real shopping items. */
async function ingredients(product, depth = 0) {
  if (depth > 3) return [{ name: product, quantity: 1 }];
  const mats = await fetchRecipe(product);
  if (!mats) return null;
  const out = [];
  for (const mat of mats) {
    if (SKIP_MATS.test(mat.name)) continue;
    if (EXPAND.test(mat.name)) {
      const sub = await ingredients(mat.name, depth + 1);
      if (sub) {
        for (const s of sub) out.push({ name: s.name, quantity: s.quantity * mat.quantity });
        continue;
      }
    }
    out.push(mat);
  }
  return out.length ? out : null;
}

const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));
let applied = 0;
let notes = 0;
for (const [subId, subProducts] of products) {
  const stepId = subId.split(':')[0];
  const existing = annotations.annotations[stepId]?.items || [];
  // Accumulate mats across ALL the sub's products FIRST (shared mats
  // sum: 13 dyes = 65 coins), then merge against existing annotations.
  const totals = new Map(); // mat name -> total quantity
  const noteLines = [];
  for (const product of subProducts) {
    const mats = await ingredients(product.name);
    if (!mats) {
      console.log(`miss  ${subId} "${product.name}" (no wiki recipe) | ${product.text.slice(0, 60)}`);
      continue;
    }
    for (const m of mats) {
      const key = m.name.toLowerCase();
      totals.set(key, (totals.get(key) || 0) + m.quantity * product.qty);
    }
    noteLines.push(`Make ${cap(product.name)}: `
      + mats.map((m) => `${m.quantity}x ${cap(m.name)}`).join(' + ')
      + (product.qty > 1 ? ' each.' : '.'));
  }
  if (totals.size === 0) {
    continue;
  }
  // Method NOTE, one line per product recipe. Hand-authored notes are
  // never overwritten.
  if (!annotations.annotations[stepId]?.note && noteLines.length) {
    const noteText = noteLines.join('\n');
    console.log(`NOTE  ${subId} ${noteText.replace(/\n/g, ' | ')}`);
    if (!process.argv.includes('--dry-run')) {
      annotations.annotations[stepId] = {
        ...(annotations.annotations[stepId] || {}),
        note: noteText,
      };
      notes++;
    }
  }
  // EXISTING annotation items that are this step's mats gain the
  // ingredient flag too (earlier runs predate it) — the panel indents
  // them under the products with a carried-only count.
  let flagged = 0;
  for (const e of existing) {
    if (totals.has((e.name || '').toLowerCase()) && !e.ingredient) {
      if (!process.argv.includes('--dry-run')) {
        e.ingredient = true;
      }
      flagged++;
    }
  }
  if (flagged > 0) {
    console.log(`FLAG  ${subId} ${flagged} existing item(s) marked ingredient`);
    if (!process.argv.includes('--dry-run')) {
      annotations.annotations[stepId].items = existing;
      applied++;
    }
  }
  const scaled = [...totals.entries()]
    .map(([name, quantity]) => ({ name, quantity, ingredient: true }))
    .filter((m) => !existing.some((e) => (e.name || '').toLowerCase() === m.name));
  if (!scaled.length) {
    console.log(`skip  ${subId} (items already annotated)`);
    continue;
  }
  console.log(`HIT   ${subId} -> `
    + scaled.map((m) => `${m.name} x${m.quantity}`).join(', ')
    + ` | ${subProducts[0].text.slice(0, 50)}`);
  if (!process.argv.includes('--dry-run')) {
    annotations.annotations[stepId] = {
      ...(annotations.annotations[stepId] || {}),
      items: [...existing, ...scaled],
    };
    applied++;
  }
}
if (applied > 0 || notes > 0) {
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
}
console.log(`\napplied ingredient lists to ${applied} step(s), notes to ${notes}`);
