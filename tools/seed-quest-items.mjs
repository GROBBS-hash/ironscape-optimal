#!/usr/bin/env node
// Seeds QUEST ITEM REQUIREMENTS: each quest page's {{Quest details}}
// infobox lists required items ("*3 [[Oak logs]]..."). Those land as
// annotation items on the FIRST guide step tagged with the quest, so
// the panel shows the same have/need badges as everywhere else.
//
// Required items only (recommended lists are noise). Existing
// annotation items are merged, never overwritten. --dry-run prints.
//
// Usage: node tools/seed-quest-items.mjs [--dry-run]

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const ANNOTATIONS_FILE = path.join(RES, 'annotations/annotations_oziris.json');
const USER_AGENT = 'ironscape-runelite-plugin dev tooling (quest item seeding)';
const REQUEST_DELAY_MS = 700;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const runText = (rs) => (rs || []).map((r) => r.text).join('');

// quest name -> first step id tagged with it
const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const firstStepByQuest = new Map();
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  const quest = step.metadata?.quest?.trim();
  if (quest && !firstStepByQuest.has(quest)) {
    firstStepByQuest.set(quest, stepId(runText(step.content)));
  }
})));
console.log(`${firstStepByQuest.size} quests tagged in the guide`);

// Infobox entries that are skills, categories or prose — never items.
const DROP = new Set(['magic', 'ranged', 'woodcutting', 'combat', 'combat style',
  'combat equipment', 'weapon', 'food', 'light source', 'water', 'sword', 'arrow',
  'dye', 'tai bwo wannai village', 'ancient magicks', 'blood burst', 'fire wave',
  'tool leprechaun', 'poison (item)', 'draynor skull', 'pvm', 'stab weapon', 'slash weapon', 'bow', 'arrows', 'combat classes', 'boat', 'cannon (facility)', 'ring', 'telekinetic grab', 'ores', 'astral contact', 'snare', 'prayer points', 'mourner gear', 'crush weapon']);

async function fetchQuestItems(quest) {
  await sleep(REQUEST_DELAY_MS);
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=1&page='
    + encodeURIComponent(quest);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  const wikitext = (await res.json())?.parse?.wikitext?.['*'] || '';
  // |items = *3 [[Oak logs]]\n*[[Hammer]]... up to the next |param line
  const items = wikitext.match(/\|\s*items\s*=\s*([\s\S]*?)\n\s*\|\s*[a-z]+\s*=/i);
  if (!items) return null;
  const out = [];
  for (const line of items[1].split('\n')) {
    const entry = line.trim();
    if (!entry.startsWith('*') || /^\*\s*none/i.test(entry)) continue;
    if (entry.startsWith('**')) continue; // sub-notes ("**or [[X]]")
    const qty = entry.match(/^\*+\s*(\d[\d,]*)\s*(?:x\s*)?/);
    const link = entry.match(/\[\[([^\]|#]+)/) || entry.match(/\{\{plink\|([^}|]+)/i);
    if (!link) continue;
    const name = link[1].trim().toLowerCase();
    if (name.length < 3 || name.length > 40) continue;
    if (DROP.has(name) || name === quest.toLowerCase() || name.includes('_')) continue;
    out.push({ name, quantity: qty ? +qty[1].replace(/,/g, '') : 1 });
  }
  return out.length ? out : null;
}

const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));
let applied = 0;
for (const [quest, sid] of [...firstStepByQuest.entries()].sort()) {
  const existing = annotations.annotations[sid]?.items || [];
  const items = await fetchQuestItems(quest);
  if (!items) {
    console.log(`miss  ${quest}`);
    continue;
  }
  const scaled = items.filter((m) =>
    !existing.some((e) => (e.name || '').toLowerCase() === m.name));
  if (!scaled.length) {
    console.log(`skip  ${quest} (already annotated)`);
    continue;
  }
  console.log(`HIT   ${quest} [${sid}] -> `
    + scaled.map((m) => `${m.name} x${m.quantity}`).join(', '));
  if (!process.argv.includes('--dry-run')) {
    annotations.annotations[sid] = {
      ...(annotations.annotations[sid] || {}),
      items: [...existing, ...scaled],
    };
    applied++;
  }
}
if (applied > 0) {
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
}
console.log(`\napplied quest items to ${applied} step(s)`);
