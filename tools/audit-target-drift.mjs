// Flags steps whose destination is likely HIJACKED by a place/item-source
// name in their text.
//
// targetFor prefers an explicit ⌖, then a place name found in the step
// text, then the step's 📍 area tag. When a step has no ⌖ and its text
// happens to contain a word that is ALSO a nav name, the text wins — and
// if that name is pinned somewhere far away, nav and the first-leg
// teleport hints reason correctly from the wrong destination.
//
// Live example: "Put pineapples into the compost bin", tagged 📍Catherby.
// "compost" is an item_sources entry pinned at Vannah in Hosidius, ~1,900
// tiles away, so the hint offered a Tithe Farm teleport (the nearest
// Grouping stop to Hosidius) instead of walking 20 tiles up the hill.
//
// A ⌖ target on the step fixes it, which is what this list is for.
//
//   node tools/audit-target-drift.mjs [--all]
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const res = path.join(root, 'src/main/resources/com/ironscape');
const read = p => JSON.parse(fs.readFileSync(path.join(res, p), 'utf8'));

const guide = read('guide/guide_data_oziris.json');
const annotations = read('annotations/annotations_oziris.json').annotations;
const places = read('places/places.json').places;
const sources = read('places/item_sources.json').places;

const stepId = text => crypto.createHash('sha256')
  .update(text.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8')
  .digest('hex').slice(0, 10);

// Every nav name -> its pin. item_sources shares the place namespace.
const navNames = new Map();
for (const [key, value] of Object.entries({ ...places, ...sources })) {
  if (value && typeof value.x === 'number' && value.type !== 'transport') {
    navNames.set(key.toLowerCase(), value);
  }
}

// Quest names are place links on purpose (they route to the giver), so a
// far pin there is designed behaviour rather than a hijacked destination.
const questNames = new Set();
try {
  const givers = read('places/quest_givers.json');
  for (const key of Object.keys(givers.givers || givers)) {
    questNames.add(key.toLowerCase());
  }
} catch (e) {
  // no quest-giver file: every name is treated as a candidate
}

// Subs whose destination is decided by the QUEST branch, not by any place
// name in the text. targetFor routes an unstarted quest goal to the
// quest's GIVER, and once it is in progress the route stands down for
// Quest Helper — so the text pin never gets a vote either way.
//
// Without this the audit reported "Start the Lost tribe, do until you need
// to go to Varrock" as a 206-tile hijack, when the live code routes it to
// Duke Horacio in Lumbridge exactly as designed. A list with confident
// non-bugs in it is one nobody finishes reading.
const questRouted = new Set();
try {
  const paths = fs.readFileSync(path.join(root, 'build/completion-paths.tsv'), 'utf8');
  for (const line of paths.split('\n')) {
    const [, subId, kind] = line.split('\t');
    if (subId && (kind === 'quest-start' || kind === 'quest-finish')) {
      questRouted.add(subId.split(':')[0]);
    }
  }
} catch (e) {
  // no dump: fall back to reporting them (better loud than silently blind)
}

// Mirrors IronscapePlugin.STOPPING_POINT: "…until you need to go to X"
// names where you STOP. The plugin strips it before matching places, so
// the audit must too or it flags a route the code no longer takes.
const STOPPING_POINT = /\buntil\s+you\s+(?:need|have)\s+to\b.*$/i;

const dist = (a, b) => Math.round(Math.hypot(a.x - b.x, a.y - b.y));
const DRIFT = 200; // beyond this, the text pin is a different region entirely

const all = process.argv.includes('--all');
const rows = [];

for (const chapter of guide.chapters) {
  for (const section of chapter.sections) {
    for (const step of section.steps) {
      const text = (step.content || []).map(r => r.text).join('');
      const id = stepId(text);
      // An explicit ⌖ (step or any sub) already wins — nothing to hijack.
      // So does an errand chain, which outranks nav entirely.
      const settled = Object.keys(annotations).some(k =>
        (k === id || k.startsWith(id + ':'))
        && (annotations[k].target || annotations[k].errands));
      if (settled) continue;

      if (questRouted.has(id)) continue; // the quest branch owns this one

      const tag = (step.metadata || {}).location;
      const area = tag ? navNames.get(tag.toLowerCase()) : null;
      if (!area) continue; // no area to compare against

      const lower = text.toLowerCase().replace(STOPPING_POINT, '');
      const ownQuest = ((step.metadata || {}).quest || '').toLowerCase();
      let hit = null;
      for (const [name, pin] of navNames) {
        if (name.length < 5 || !lower.includes(name)) continue;
        // A step's OWN quest name routing to that quest's giver is the
        // designed behaviour, not a hijack.
        if (ownQuest && (name === ownQuest || ownQuest.includes(name))) continue;
        if (questNames.has(name)) continue;
        const drift = dist(pin, area);
        if (drift > DRIFT && (!hit || drift > hit.drift)) {
          hit = { name, pin, drift };
        }
      }
      if (hit) rows.push({ id, tag, text, ...hit });
    }
  }
}

rows.sort((a, b) => b.drift - a.drift);
console.log('=== steps with no ⌖ whose TEXT names a far-away nav pin ===');
for (const r of (all ? rows : rows.slice(0, 25))) {
  console.log(`  ${r.drift.toString().padStart(5)} tiles  [${r.id}] 📍${r.tag}`);
  console.log(`         text pin "${r.name}" -> ${r.pin.x},${r.pin.y}`);
  console.log(`         ${r.text.slice(0, 88)}`);
}
console.log(`\n${rows.length} steps where the text pin sits >${DRIFT} tiles from the step's own area`
  + (all || rows.length <= 25 ? '' : ' (--all to list)'));
