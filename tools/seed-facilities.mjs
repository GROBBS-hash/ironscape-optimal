#!/usr/bin/env node
// Seeds FACILITY TARGETS: steps like "make molten glass at edgeville
// furnace" or "make unfired bowl at barb village" name a town + a
// facility (furnace, anvil, spinning wheel, range, altar...). The wiki's
// facility pages carry a map pin for every instance in the game; the
// pin NEAREST the step's town (whose coordinates places.json already
// knows) is that step's target. Proximity is the review: a pin more
// than MAX_TOWN_DISTANCE tiles from the town is rejected.
//
// Usage: node tools/seed-facilities.mjs [--dry-run]
// Applies directly (unlike seed-shops) because the town-proximity rule
// leaves no judgement call; existing targets are never overwritten.

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GUIDE_FILE = path.join(__dirname, '../src/main/resources/com/ironscape/guide/guide_data_oziris.json');
const ANNOTATIONS_FILE = path.join(__dirname, '../src/main/resources/com/ironscape/annotations/annotations_oziris.json');
const PLACES_FILE = path.join(__dirname, '../src/main/resources/com/ironscape/places/places.json');

const USER_AGENT = 'ironscape-runelite-plugin dev tooling (facility seeding)';
const REQUEST_DELAY_MS = 700;
const MAX_TOWN_DISTANCE = 80;
// Mirrors IronscapePlugin.SURFACE_MAX_Y: above this you are underground,
// and distances across the boundary are fiction.
const SURFACE_MAX_Y = 4000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const runText = (runs) => (runs || []).map((r) => r.text).join('');

const FACILITY_PAGES = {
  'furnace': 'Furnace',
  'anvil': 'Anvil',
  'spinning wheel': 'Spinning wheel',
  // "Range (cooking)" is not a page — the fetch 404'd and the tool
  // reported "0 surface pins", which reads identically to "the wiki has
  // no data" and is how this sat unexamined. It IS "Range"; that page
  // genuinely carries no map templates, so ranges still cannot be
  // seeded, but now for the real reason.
  'range': 'Range',
  'altar': 'Altar',
  'pottery wheel': 'Potter\'s wheel',
  'pottery oven': 'Pottery oven',
  'loom': 'Loom',
  'windmill': 'Windmill',
};
const FACILITY_RE = new RegExp('\\b(' + Object.keys(FACILITY_PAGES).join('|') + ')\\b', 'i');

// The guide usually states the PRODUCT and leaves the equipment implied:
// "Make 5 molten glass" needs a furnace and never says the word, so the
// pattern above could not see it and the owner had to pin it by hand in
// play. Every pin a person sets is one every future user would have had
// to set too, which is the thing this tool exists to prevent.
//
// High-precision on purpose: a loose rule sends people to the wrong
// building, which is worse than sending them nowhere. Each phrase names
// a product that cannot be made anywhere else. Note "molten glass" needs
// its verb -- BLOWING it into orbs uses a pipe and happens anywhere.
// Kept in step with tools/audit-implied-facilities.mjs, which measures
// the class and is where a new phrase should be justified first.
const IMPLIED_FACILITY = [
  ['furnace', /\b(?:smelt|mak(?:e|ing) \d* ?molten glass|make \d* ?(?:bronze|iron|steel|silver|gold|mithril|adamant|rune) bars?|cannonballs?)\b/i],
  // The metal need not follow "smith" immediately: "Smith the rest of your
  // iron dart tips" is an anvil step and the strict form missed it. Bounded
  // to the same sentence so a later unrelated metal cannot pull one in.
  ['anvil', /\bsmith(?:ing)?\b[^.]{0,40}\b(?:bronze|iron|steel|mithril|adamant|rune)\b/i],
  ['spinning wheel', /\bspin (?:\d+ )?(?:ball of wool|wool|flax|bow ?string)/i],
  ['pottery wheel', /\b(?:unfired|make (?:\d+ )?(?:pot|pie dish|bowl)s?)\b/i],
  ['windmill', /\b(?:grind (?:\d+ )?wheat|make (?:\d+ )?flour|pot of flour)\b/i],
  ['loom', /\bweave\b/i],
  ['range', /\bcook (?:\d+ )?(?:the )?(?:raw|shrimp|trout|salmon|lobster|meat|karambwan)/i],
];

// "range" is the single most dangerous word in this list, because in this
// guide it is almost always the SKILL: "Get range void from PC", "chin to
// at least 87 range", "Do Turael slayer until 37 Range". Six steps match
// it that way and none of them wants a cooking range. They have only ever
// been rejected because the wiki page happened to return no pins -- luck,
// not correctness, and the day that page resolves the seeder would send
// people to a fire in Lumbridge to get void. So a range needs COOKING
// evidence in the same step before it counts.
const COOKS = /\b(?:cook|bake|nettle|bowl of water|raw )\b/i;

/** The facility a step needs: named outright, else implied by what it makes. */
// "Mind altar" is a PLACE, and the step "safespot a bear for its meat
// south of mind altar" wants no altar at all. Same shape as the "range"
// trap wave 18 caught: the facility word is real, the sentence is about
// something else. That one was caught only because the Altar page
// happens to publish no pins — luck, not correctness — so name it.
//
// Data-driven, not a word list: if the facility word together with what
// precedes it is a name places.json already knows, the step is talking
// about that place.
function namesAPlace(text, facility) {
  const words = key(text).split(' ');
  for (let i = 0; i < words.length; i++) {
    if (words[i] !== facility) continue;
    for (let back = 1; back <= 2; back++) {
      if (i - back < 0) break;
      if (places[words.slice(i - back, i + 1).join(' ')]) return true;
    }
  }
  return false;
}

function facilityFor(text) {
  const named = text.match(FACILITY_RE);
  if (named) {
    const facility = named[1].toLowerCase();
    if (facility === 'range' && !COOKS.test(text)) return null;
    if (namesAPlace(text, facility)) return null;
    return facility;
  }
  const implied = IMPLIED_FACILITY.find(([, re]) => re.test(text));
  return implied ? implied[0] : null;
}

const guide = JSON.parse(fs.readFileSync(GUIDE_FILE, 'utf8'));
const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));
const places = JSON.parse(fs.readFileSync(PLACES_FILE, 'utf8')).places;

const key = (s) => s.toLowerCase().replace(/’/g, "'").replace(/[^a-z0-9' ]/g, ' ').replace(/\s+/g, ' ').trim();

// The town a step means: a known place named in its text, else its 📍 tag.
function townOf(text, location) {
  const lt = ' ' + key(text) + ' ';
  let best = null;
  for (const [name, p] of Object.entries(places)) {
    if (name.length >= 4 && lt.includes(' ' + name + ' ')) {
      // Prefer the LONGEST match ("barbarian village" over "barb")
      if (!best || name.length > best.name.length) best = { name, ...p };
    }
  }
  if (best) return best;
  if (location) {
    const k = key(location.replace(/^(north|south|east|west)([ -](east|west))?\s+of\s+/i, ''));
    if (places[k]) return { name: k, ...places[k] };
  }
  return null;
}

// Collect facility steps that still lack a target.
const wanted = [];
for (const ch of guide.chapters) {
  for (const sec of ch.sections) {
    for (const step of sec.steps) {
      const text = runText(step.content);
      const facility = facilityFor(text);
      if (!facility) continue;
      const id = stepId(text);
      if (annotations.annotations[id]?.target) continue;
      const town = townOf(text, step.metadata?.location);
      if (!town) continue;
      wanted.push({ id, text: text.slice(0, 80), facility, town });
    }
  }
}
console.log(`${wanted.length} facility step(s) without a target`);

// Fetch each needed facility page once and collect its surface pins.
const pinsByFacility = {};
for (const facility of new Set(wanted.map((w) => w.facility))) {
  await sleep(REQUEST_DELAY_MS);
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=1&page='
    + encodeURIComponent(FACILITY_PAGES[facility]);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  const wikitext = (await res.json())?.parse?.wikitext?.['*'] || '';
  const pins = [];
  for (const tpl of wikitext.matchAll(/\{\{(?:Object[ _]map|Map)\s*\|([^{}]*)\}\}/gi)) {
    const body = tpl[1];
    // mapID names WHICH map the pins are on, and 0 is the ordinary
    // surface — so "skip anything with a mapID" threw away almost every
    // real pin. The Anvil page carries 61 map templates and this yielded
    // FOUR, which is why anvils, ranges and potter's wheels all looked
    // like "the wiki has no location data" (wave 18). It has plenty.
    //
    // Read from the coordinates, not from a guess about the parameter:
    // the mapID=0 blocks hold x:3188,y:3426 and x:3228,y:3436 (Varrock's
    // two anvils), while mapID=-1 and mapID=29 hold y:9489 and y:6055 —
    // underground and other-plane maps. So drop a NON-ZERO mapID only.
    // Other maps (Keldagrim is mapID=10, dungeons -1) are KEPT but ranked
    // below the surface, so a surface town behaves exactly as before and
    // only a town the surface map cannot serve reaches them. Keldagrim's
    // anvils sit 26 tiles from its pin and were previously unreachable at
    // any distance.
    const mapId = body.match(/mapID\s*=\s*(-?\d+)/i);
    const surface = !mapId || mapId[1] === '0';
    // One template usually lists SEVERAL pins ("x:3246,y:3404|x:3248,
    // y:3404|..."); taking only the first lost most of them, and the one
    // kept is not necessarily the one nearest the step's town.
    const labelled = [...body.matchAll(/x\s*[=:]\s*(\d{3,5})\s*[,|]\s*y\s*[=:]\s*(\d{3,5})/gi)];
    if (labelled.length) {
      for (const m of labelled) {
        pins.push({ x: +m[1], y: +m[2], surface });
      }
      continue;
    }
    for (const m of body.matchAll(/(?:^|\|)\s*(\d{3,5})\s*[,:]\s*(\d{3,5})\s*(?=\||$)/g)) {
      pins.push({ x: +m[1], y: +m[2], surface });
    }
  }
  pinsByFacility[facility] = pins;
  console.log(`  ${facility}: ${pins.length} surface pin(s)`);
}

let applied = 0;
for (const w of wanted) {
  const pins = pinsByFacility[w.facility] || [];
  let best = null;
  for (const pin of pins) {
    // Compare within the town's own BAND, the same rule nearestOf uses in
    // the plugin. Filtering pins to y<8000 up front looked like "surface
    // only" but really meant "no underground town can ever be served":
    // Keldagrim sits at y~10200, so every one of its anvils was discarded
    // before the proximity test ran. A town underground wants the
    // underground pins and nothing else, and vice versa.
    if ((pin.y < SURFACE_MAX_Y) !== (w.town.y < SURFACE_MAX_Y)) continue;
    const d = Math.max(Math.abs(pin.x - w.town.x), Math.abs(pin.y - w.town.y));
    if (d > MAX_TOWN_DISTANCE) continue;
    // Surface-map pins win outright; another map's pin is only ever a
    // fallback, so nothing that works today can be displaced by one.
    const better = !best
      || (pin.surface && !best.surface)
      || (pin.surface === best.surface && d < best.d);
    if (better) best = { ...pin, d };
  }
  if (!best) {
    console.log(`miss ${w.id} ${w.facility} @ ${w.town.name} | ${w.text}`);
    continue;
  }
  console.log(`HIT  ${w.id} ${w.facility} @ ${w.town.name} -> ${best.x},${best.y} (${best.d} tiles) | ${w.text}`);
  if (!process.argv.includes('--dry-run')) {
    annotations.annotations[w.id] = {
      ...(annotations.annotations[w.id] || {}),
      target: { x: best.x, y: best.y, plane: 0 },
    };
    applied++;
  }
}
if (applied > 0) {
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
}
console.log(`applied ${applied} facility target(s)`);
