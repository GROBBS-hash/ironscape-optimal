#!/usr/bin/env node
// Which steps need a FACILITY they never name?
//
// seed-facilities.mjs finds a step's furnace or anvil by looking for the
// WORD in the step text ("make unfired bowl at barb village"). But the
// guide usually states the product and leaves the equipment implied:
// "Make 5 molten glass" needs a furnace and never says so, which is why
// the owner had to capture that pin by hand in play.
//
// Manual capture is the thing this project is trying to eliminate -- a
// pin the owner sets is a pin every future user would otherwise have to
// set too -- so the implied cases are worth finding as a class rather
// than one play session at a time.
//
// Reports only steps with NO target already, since a captured or seeded
// pin makes the question moot.
//
//   node tools/audit-implied-facilities.mjs
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');

const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annotations = JSON.parse(fs.readFileSync(path.join(RES, 'annotations/annotations_oziris.json'), 'utf8')).annotations;
const places = JSON.parse(fs.readFileSync(path.join(RES, 'places/places.json'), 'utf8')).places;

const sid = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);

// The facility each ACTION implies. Kept high-precision on purpose: a
// loose pattern here would send users to the wrong building, which is
// worse than sending them nowhere. Every entry is a phrase whose product
// cannot be made anywhere else.
const IMPLIED = [
  // "molten glass" alone is NOT a furnace step: BLOWING it into orbs and
  // lenses takes a glassblowing pipe and happens anywhere. Only MAKING it
  // needs the furnace, so the verb has to be there ("blow all of it into
  // unpowered orbs" was the false positive this cost).
  ['furnace', /\b(?:smelt|mak(?:e|ing) \d* ?molten glass|make \d* ?(?:bronze|iron|steel|silver|gold|mithril|adamant|rune) bars?|cannonballs?)\b/i],
  ['anvil', /\bsmith(?:ing)?\b[^.]{0,40}\b(?:bronze|iron|steel|mithril|adamant|rune)\b/i],
  ['spinning wheel', /\bspin (?:\d+ )?(?:ball of wool|wool|flax|bow ?string)/i],
  ['pottery wheel', /\b(?:unfired|make (?:\d+ )?(?:pot|pie dish|bowl)s?)\b/i],
  ['windmill', /\b(?:grind (?:\d+ )?wheat|make (?:\d+ )?flour|pot of flour)\b/i],
  ['loom', /\bweave\b/i],
  ['range', /\bcook (?:\d+ )?(?:the )?(?:raw|shrimp|trout|salmon|lobster|meat|karambwan)/i],
];

// The seeder's own trigger: the facility named outright. Those are its
// job already, so they are not findings here.
const NAMED = /\b(furnace|anvil|spinning wheel|range|altar|pottery wheel|pottery oven|loom|windmill)\b/i;

const pkey = (s) => s.toLowerCase().trim().replace(/’/g, "'");
const resolvable = (name) => !!(name && places[pkey(name)]);

const rows = [];
const counts = new Map();
let idx = 0;
for (const ch of guide.chapters) {
  for (const sec of ch.sections) {
    for (const st of sec.steps) {
      const text = (st.content || []).map((c) => c.text).join('');
      let id = sid(text);
      const seen = (counts.get(id) || 0) + 1; counts.set(id, seen);
      if (seen > 1) id = `${id}-${seen}`;

      const hit = IMPLIED.find(([, re]) => re.test(text));
      if (!hit) { idx++; continue; }
      if (NAMED.test(text)) { idx++; continue; }        // seeder's job already

      const hasTarget = (annotations[id] && annotations[id].target)
        || (annotations[`${id}:0`] && annotations[`${id}:0`].target);
      const location = st.metadata?.location ?? null;
      rows.push({
        idx, id, text, facility: hit[0], hasTarget: !!hasTarget,
        location, townKnown: resolvable(location),
      });
      idx++;
    }
  }
}

const open = rows.filter((r) => !r.hasTarget);
const seedable = open.filter((r) => r.townKnown);

console.log('=== STEPS THAT IMPLY A FACILITY BUT NEVER NAME IT ===\n');
for (const r of rows) {
  const state = r.hasTarget ? 'pinned  ' : (r.townKnown ? 'SEEDABLE' : 'no town ');
  console.log(`  ${state}  ${r.facility.padEnd(15)} #${String(r.idx).padStart(3)}  ${r.text.slice(0, 66)}`);
  if (!r.hasTarget) console.log(`              📍 ${r.location ?? '(none)'}`);
}

console.log(`\n${rows.length} steps imply a facility.`);
console.log(`  ${rows.length - open.length} already have a target (captured or seeded).`);
console.log(`  ${seedable.length} have no target AND a town the seeder can anchor to — these are auto-seedable.`);
console.log(`  ${open.length - seedable.length} have no target and no resolvable town — these need a person.`);
