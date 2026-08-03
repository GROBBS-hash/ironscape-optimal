#!/usr/bin/env node
// Seeds QUEST GIVER names: the wiki's {{Quest details|start = Talk to
// [[Kovac]] at...}} names the NPC who starts each quest. With the name
// known, the panel outlines the actual giver instead of guessing
// "whoever stands nearest the quest-start pin" (a decorative giant at
// the Foundry got the quest icon).
//
// Scans the guide's quest metadata tags + writes
// src/main/resources/com/ironscape/places/quest_givers.json
//
// Usage: node tools/seed-quest-givers.mjs [--dry-run]

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const OUT_FILE = path.join(RES, 'places/quest_givers.json');
const USER_AGENT = 'ironscape-runelite-plugin dev tooling (quest giver seeding)';
const REQUEST_DELAY_MS = 700;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const quests = new Set();
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  if (step.metadata?.quest) quests.add(step.metadata.quest.trim());
})));
console.log(`${quests.size} quests tagged in the guide`);

const existing = fs.existsSync(OUT_FILE)
  ? JSON.parse(fs.readFileSync(OUT_FILE, 'utf8')) : { version: 1, givers: {} };

let added = 0;
for (const quest of [...quests].sort()) {
  const key = quest.toLowerCase();
  if (existing.givers[key]) continue;
  await sleep(REQUEST_DELAY_MS);
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=1&page='
    + encodeURIComponent(quest);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  const wikitext = (await res.json())?.parse?.wikitext?.['*'] || '';
  // |start = Talk to [[Kovac]] at the [[Giants' Foundry]] — the first
  // wikilink in the start text is the giver.
  const start = wikitext.match(/\|\s*start\s*=\s*([^\n]*)/);
  const link = start?.[1]?.match(/\[\[([^\]|#]+)/);
  if (!link) {
    console.log(`miss  ${quest}`);
    continue;
  }
  const giver = link[1].trim();
  // A first link that's a PLACE would outline nothing (no NPC by that
  // name in the scene) — harmless, but skip the obvious ones.
  if (/castle|village|town|city|island|guild|house|tower|temple|monastery/i.test(giver)) {
    console.log(`skip  ${quest} -> "${giver}" (looks like a place)`);
    continue;
  }
  existing.givers[key] = giver;
  added++;
  console.log(`+ ${quest} -> ${giver}`);
}

if (!process.argv.includes('--dry-run')) {
  fs.writeFileSync(OUT_FILE, JSON.stringify(existing, null, 1) + '\n');
}
console.log(`\n${added} giver(s) added`);
