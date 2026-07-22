#!/usr/bin/env node
// Seeds the bundled places file with NPC locations from the OSRS Wiki.
//
// 1. Scans the guide text for "talk to / speak to <Name>" patterns.
// 2. Fetches each name's wiki page and reads the {{Map|x,y|...|plane=N}}
//    coordinates out of its infobox.
// 3. Merges hits into src/main/resources/com/bruhsailer/places/places.json
//    (existing entries are never overwritten — your in-game captures win).
//
// Usage: node tools/seed-places.mjs [--dry-run] [--quests] [--locations] [--links] [--pois]
//   --dry-run:   only print the names it would look up.
//   --quests:    instead of NPCs, seed every quest name with its start
//                point (from the wiki's Category:Quests), so quest names
//                in the guide become clickable links to where they begin.
//   --locations: seed location names — capitalized phrases found in the
//                guide ("Lumber Yard") plus a curated list of cities and
//                areas — with coordinates from their wiki pages.
//   --links:     POI harvest, source 1 — every oldschool.runescape.wiki
//                hyperlink the guide authors embedded. The link TEXT is
//                what the panel must match, the URL names the exact wiki
//                page, so there's no name-guessing: "Shayzien Styles" ->
//                w/Shayzien_Styles -> its {{Map}} coordinates.
//   --pois:      POI harvest, source 2 — lowercase-suffix phrases the
//                --locations capitalized scan can't see ("the Shayzien
//                agility course"), looked up with a Title Case fallback.

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GUIDE_FILE = path.join(__dirname, '../src/main/resources/com/bruhsailer/guide/guide_data.json');
const PLACES_FILE = path.join(__dirname, '../src/main/resources/com/bruhsailer/places/places.json');

const USER_AGENT = 'BRUHsailer-runelite-plugin dev tooling (one-off seeding script)';
const REQUEST_DELAY_MS = 600; // be polite to the wiki

// ---------------------------------------------------------------------
// 1. Collect NPC names the guide actually tells you to talk to
// ---------------------------------------------------------------------

const guide = JSON.parse(fs.readFileSync(GUIDE_FILE, 'utf8'));
const runText = (runs) => (runs || []).map((r) => r.text).join('');

// "Talk to Duke Horacio", "speak with Father Aereck", "return to Juliet"
// Name = capitalized words, allowing connectors (of, the) inside.
// Case-insensitive verbs: "Speak with Juliet" at sentence start counts too.
const NAME_PATTERN = /\b(?:talk|speak|return|give|bring|show|deliver|report)\s+(?:to|with|for)\s+((?:[A-Z][A-Za-z'’-]+)(?:\s+(?:of|the|[A-Z][A-Za-z'’-]+))*)/gi;

const mentions = new Map(); // name -> count
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  const text = [
    runText(step.content),
    ...(step.nestedContent || []).map((n) => runText(n.content)),
    ...(step.additionalContent || []).map(runText),
  ].join('\n');
  for (const m of text.matchAll(NAME_PATTERN)) {
    // strip trailing connectors the regex may have swallowed
    const name = m[1].replace(/\s+(?:of|the)$/, '').trim();
    if (name.length < 3 || name.length > 30) continue;
    mentions.set(name, (mentions.get(name) || 0) + 1);
  }
})));

// ---------------------------------------------------------------------
// 2. Look each name up on the wiki
// ---------------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function listQuestTitles() {
  const titles = [];
  // Miniquests (Daddy's Home etc.) live in their own category.
  for (const category of ['Category%3AQuests', 'Category%3AMiniquests']) {
    let cmcontinue;
    do {
      const url = 'https://oldschool.runescape.wiki/api.php?action=query&list=categorymembers'
        + `&cmtitle=${category}&cmlimit=500&cmnamespace=0&format=json`
        + (cmcontinue ? '&cmcontinue=' + encodeURIComponent(cmcontinue) : '');
      const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
      const json = await res.json();
      titles.push(...json.query.categorymembers.map((m) => m.title));
      cmcontinue = json.continue?.cmcontinue;
      await sleep(REQUEST_DELAY_MS);
    } while (cmcontinue);
  }
  return titles;
}

// Cities/areas the guide mentions as single words or with lowercase glue
// that the phrase scan below can't catch.
const CURATED_LOCATIONS = [
  'Varrock', 'Lumbridge', 'Falador', 'Catherby', 'Taverley', 'Edgeville',
  'Draynor Village', 'Al Kharid', 'Rimmington', 'Burthorpe', "Seers' Village",
  'Yanille', 'Camelot', 'Canifis', 'Varlamore', 'Civitas illa Fortis',
  'Lumber Yard', 'Sawmill', 'Grand Exchange', 'Barbarian Village',
  'Ferox Enclave', 'Mage Arena', "Champions' Guild", 'Mage Training Arena',
  'Castle Wars', 'Brimhaven', 'Shilo Village', 'Tree Gnome Stronghold',
  'Tree Gnome Village', 'Wizards’ Tower', 'Musa Point', 'Karamja',
  'Port Khazard', 'Witchaven', 'Sophanem', 'Nardah', 'Prifddinas',
];

// Leading verbs to strip off phrase candidates: "Enter the Lumber Yard"
// is about the Lumber Yard.
const LEADING_VERBS = /^(?:Enter|Travel|Head|Go|Use|Take|Run|Walk|Return|Teleport|Climb|Cross|Speak|Talk|Buy|Grab|Complete|Start|Finish|Do|Kill|Open|Once|Then|Optional|Note|Visit|Reach)\s+(?:the\s+|to\s+|back\s+|with\s+|into\s+)*/;

function locationCandidates() {
  const found = new Map();
  guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
    const text = [
      runText(step.content),
      ...(step.nestedContent || []).map((n) => runText(n.content)),
      ...(step.additionalContent || []).map(runText),
    ].join('\n');
    // Two or more capitalized words (allowing of/the between them).
    for (const m of text.matchAll(/\b([A-Z][A-Za-z'’]+(?:\s+(?:of|the|[A-Z][A-Za-z'’]+))+)\b/g)) {
      let name = m[1].replace(LEADING_VERBS, '').replace(/\s+(?:the|of)$/, '').trim();
      const words = name.split(/\s+/);
      // scraped phrases must be 2-4 words, each starting uppercase or a
      // connector — single words come from the curated list instead
      if (words.length < 2 || words.length > 4) continue;
      if (!words.every((w) => /^[A-Z]/.test(w) || w === 'of' || w === 'the')) continue;
      if (name.length < 6 || name.length > 35) continue;
      found.set(name, (found.get(name) || 0) + 1);
    }
  })));
  for (const c of CURATED_LOCATIONS) found.set(c, found.get(c) || 1);
  return [...found.keys()].sort();
}

// ---------------------------------------------------------------------
// POI harvest source 1: wiki links the guide authors embedded.
// The panel matches the link TEXT against places.json keys, so the text
// is the key; the URL removes all ambiguity about WHICH wiki page.
// Returns Map of display text -> wiki page title.
// ---------------------------------------------------------------------

function linkCandidates() {
  const found = new Map();
  const eachRun = (runs) => (runs || []).forEach((run) => {
    // The upstream generator nests the url inside "formatting".
    const url = run.url || run.formatting?.url;
    if (!url || !run.text) return;
    const m = url.match(/^https?:\/\/oldschool\.runescape\.wiki\/w\/([^#?]+)/);
    if (!m) return; // imgur, youtube, etc.
    const page = decodeURIComponent(m[1]).replace(/_/g, ' ');
    // File:/Category: etc. pages have no useful map of their own.
    if (/^(?:File|Category|Special|Template):/i.test(page)) return;
    // The authors sometimes label links "<thing> - OSRS Wiki".
    let text = run.text.replace(/\s*[-–—]\s*OSRS Wiki\s*$/i, '')
      .replace(/^[\s"'“”]+|[\s"'“”.,:;!]+$/g, '');
    // Only strip an unbalanced trailing ")" — "Chest (Aldarin Villas)"
    // keeps its parenthetical.
    if (text.endsWith(')') && !text.includes('(')) {
      text = text.slice(0, -1);
    }
    if (text.includes('(') && !text.endsWith(')')) {
      text += ')';
    }
    if (text.length < 4 || text.length > 35) return;
    if (/^(?:here|this|link|video|map|guide|wiki)$/i.test(text)) return;
    if (!found.has(text)) found.set(text, page);
  });
  guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
    eachRun(step.content);
    (step.nestedContent || []).forEach((n) => eachRun(n.content));
    (step.additionalContent || []).forEach(eachRun);
  })));
  return found;
}

// ---------------------------------------------------------------------
// POI harvest source 2: phrases built from a proper noun + a lowercase
// POI suffix ("Shayzien agility course", "Woodcutting Guild bank").
// The --locations scan needs EVERY word capitalized, so these slip by.
// ---------------------------------------------------------------------

const POI_SUFFIXES = [
  'agility course', 'agility shortcut', 'farming patch', 'mine', 'bank',
];

function poiCandidates() {
  const found = new Map();
  const suffixes = POI_SUFFIXES.join('|');
  const pattern = new RegExp(
    String.raw`\b([A-Z][A-Za-z'’]+(?:\s+[A-Za-z'’]+)?\s+(?:${suffixes}))\b`, 'g');
  guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
    const text = [
      runText(step.content),
      ...(step.nestedContent || []).map((n) => runText(n.content)),
      ...(step.additionalContent || []).map(runText),
    ].join('\n');
    for (const m of text.matchAll(pattern)) {
      const name = m[1].replace(LEADING_VERBS, '').trim();
      if (name.length < 8 || name.length > 35) continue;
      // Prose glue, not a name: "Ferox and bank", "Khazard to bank",
      // and verb-stripped leftovers that no longer start capitalized.
      if (/^[a-z]/.test(name)) continue;
      if (/\s(?:and|to|then|or)\s/.test(name)) continue;
      if (/^(?:Now|Then|Next)\s/.test(name)) continue;
      found.set(name, (found.get(name) || 0) + 1);
    }
  })));
  return [...found.keys()].sort();
}

const questMode = process.argv.includes('--quests');
const locationMode = process.argv.includes('--locations');
const linkMode = process.argv.includes('--links');
const poiMode = process.argv.includes('--pois');

// In link mode the wiki page differs from the display text; everywhere
// else they're the same string.
const linkPages = linkMode ? linkCandidates() : new Map();
const names = questMode ? await listQuestTitles()
  : locationMode ? locationCandidates()
  : linkMode ? [...linkPages.keys()].sort()
  : poiMode ? poiCandidates()
  : [...mentions.keys()].sort();
console.log(questMode
  ? `Found ${names.length} pages in the wiki's quest category.`
  : locationMode
    ? `Found ${names.length} location candidates (guide phrases + curated list).`
    : linkMode
      ? `Found ${names.length} wiki-linked names in the guide.`
      : poiMode
        ? `Found ${names.length} POI-suffix phrases in the guide.`
        : `Found ${names.length} distinct "talk to" names in the guide.`);

if (process.argv.includes('--dry-run')) {
  names.forEach((n) => console.log(linkMode ? `  ${n} -> ${linkPages.get(n)}` : `  ${n}`));
  process.exit(0);
}

async function fetchCoords(name) {
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=1&page='
    + encodeURIComponent(name);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) return null;
  const json = await res.json();
  const wikitext = json?.parse?.wikitext?.['*'];
  if (!wikitext) return null;

  // A redirect to a "Foo/Bar" subpage means we landed on a listing
  // ("Falador farming patch" -> "Farming/Patch locations") whose first
  // map is some OTHER place entirely. Better no coords than wrong ones.
  const finalTitle = json?.parse?.title || '';
  if (finalTitle !== name && finalTitle.includes('/')) return null;

  // Quest pages: {{Quest details|...|startmap = 3210,3222,plane:1|...}}
  const start = wikitext.match(/startmap\s*=\s*(\d{3,5}),\s*(\d{3,5})(?:,\s*plane:(\d))?/);
  if (start) {
    return {
      x: parseInt(start[1], 10),
      y: parseInt(start[2], 10),
      plane: start[3] ? parseInt(start[3], 10) : 0,
    };
  }

  // Map templates come in several shapes:
  //   {{Map|name=...|3208,3226|3213,3226|plane=1}}     (positional pairs)
  //   {{Map|x=3210|y=3495|plane=0|r=3}}                (named params)
  //   {{NPC map|x=3222|y=3472|r=3}}                    (different template)
  // A page can carry several. Prefer the first SURFACE one (y < 8000):
  // dungeon pages often lead with the underground map while a later
  // "entrance" map is where navigation should actually point.
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
  let x;
  let y;
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

const placesFile = JSON.parse(fs.readFileSync(PLACES_FILE, 'utf8'));
placesFile.places = placesFile.places || {};

let added = 0;
let skipped = 0;
const misses = [];

for (const name of names) {
  const key = name.toLowerCase();
  if (placesFile.places[key]) {
    skipped++;
    continue; // never clobber an existing (possibly hand-captured) entry
  }
  await sleep(REQUEST_DELAY_MS);
  try {
    // Link mode knows the exact page; other modes look the name up as-is.
    let coords = await fetchCoords(linkMode ? linkPages.get(name) : name);
    if (!coords && poiMode) {
      // The wiki titles POIs in Title Case ("Shayzien Agility Course");
      // the guide writes them in prose case. Retry titled.
      const titled = name.replace(/\b[a-z]/g, (c) => c.toUpperCase());
      if (titled !== name) {
        await sleep(REQUEST_DELAY_MS);
        coords = await fetchCoords(titled);
      }
    }
    if (!coords) {
      misses.push(name);
      continue;
    }
    placesFile.places[key] = questMode
      ? { display: name, type: 'quest', ...coords }
      : { display: name, ...coords };
    added++;
    console.log(`  + ${name} -> ${coords.x},${coords.y}${coords.plane ? ' plane ' + coords.plane : ''}`);
  } catch (e) {
    misses.push(`${name} (error: ${e.message})`);
  }
}

fs.writeFileSync(PLACES_FILE, JSON.stringify(placesFile, null, 2) + '\n');
console.log(`\nDone: ${added} added, ${skipped} already present, ${misses.length} not found.`);
if (misses.length) {
  console.log('Not found on the wiki (capture these in game with the + button):');
  misses.forEach((n) => console.log(`  - ${n}`));
}
console.log('Rebuild the plugin to bundle the new places.');
