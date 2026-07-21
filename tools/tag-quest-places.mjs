#!/usr/bin/env node
// One-off: mark which entries in the bundled places file are QUESTS
// (type: "quest"), so navigation can treat quest mentions differently
// from NPC/town mentions. Idempotent — safe to re-run.

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PLACES_FILE = path.join(__dirname, '../src/main/resources/com/bruhsailer/places/places.json');
const USER_AGENT = 'BRUHsailer-runelite-plugin dev tooling (one-off tagging script)';

const titles = [];
let cmcontinue;
do {
  const url = 'https://oldschool.runescape.wiki/api.php?action=query&list=categorymembers'
    + '&cmtitle=Category%3AQuests&cmlimit=500&cmnamespace=0&format=json'
    + (cmcontinue ? '&cmcontinue=' + encodeURIComponent(cmcontinue) : '');
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  const json = await res.json();
  titles.push(...json.query.categorymembers.map((m) => m.title.toLowerCase()));
  cmcontinue = json.continue?.cmcontinue;
} while (cmcontinue);

const questKeys = new Set(titles);
const file = JSON.parse(fs.readFileSync(PLACES_FILE, 'utf8'));

let tagged = 0;
for (const [key, place] of Object.entries(file.places)) {
  if (questKeys.has(key) && place.type !== 'quest') {
    place.type = 'quest';
    tagged++;
  }
}

fs.writeFileSync(PLACES_FILE, JSON.stringify(file, null, 2) + '\n');
console.log(`Tagged ${tagged} entries as quests (${Object.keys(file.places).length} places total).`);
