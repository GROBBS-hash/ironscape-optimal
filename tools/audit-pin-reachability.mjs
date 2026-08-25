// Which of our pins can Shortest Path actually REACH?
//
// SP walks the collision map and crosses gaps only where it has a transport --
// a door, ladder, cave entrance, boat, fairy ring. When it cannot reach a pin
// it draws NOTHING and says NOTHING: no error, no marker, no log line. From the
// player's seat that is indistinguishable from the plugin being broken, and it
// has cost three separate play sessions (ZMI wave 5, the gnome banks wave 10,
// the Fishing Contest pin up White Wolf Mountain in wave 13).
//
// The first version of this asked a PROXY question -- "is there a transport
// endpoint within 40 tiles" -- and had to report DEFINITE/LIKELY/BORDERLINE
// tiers because it could not tell walking from teleporting. It no longer
// guesses. SP publishes its collision map, so this runs the same flood fill SP
// would, across every plane, through every transport it knows, and answers:
//
//   reachable      SP can path here from Lumbridge
//   UNREACHABLE    it cannot, and here is the nearest tile it CAN reach
//
// That last part is the fix, not just the finding: an unreachable pin wants
// re-anchoring at the routable approach point, which is what ZMI, Brimstail and
// the gnome banks all needed.
//
//   node tools/audit-pin-reachability.mjs [--all]
//
// --all also lists the pins that are fine. Cached under tools/.sp-cache
// (gitignored); delete it to pick up SP's latest map.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadMap } from './lib/sp-map.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');

const showAll = process.argv.includes('--all');

// The collision map, the transport list and the flood fill from Lumbridge all
// live in lib/sp-map.mjs, so check-pin.mjs answers the same question the same
// way for a tile that is not a pin yet.
const { reachableNear, nearestReachable } = await loadMap();

// ------------------------------------------------------------ the pins
const read = (p) => JSON.parse(fs.readFileSync(path.join(RES, p), 'utf8'));
const sets = {
  places: read('places/places.json').places,
  item_sources: read('places/item_sources.json').places,
};

const stranded = [];
const fine = [];
for (const [label, map] of Object.entries(sets)) {
  for (const [name, pin] of Object.entries(map)) {
    if (!pin || typeof pin.x !== 'number' || pin.type === 'transport') continue;
    const z = pin.plane || 0;
    const near = reachableNear(pin.x, pin.y, z, 2);
    if (near) fine.push({ label, name, pin, slack: near[2] });
    else stranded.push({ label, name, pin, anchor: nearestReachable(pin.x, pin.y, z) });
  }
}
stranded.sort((a, b) => (a.anchor ? a.anchor[3] : 999) - (b.anchor ? b.anchor[3] : 999));

// Not every unreachable pin is a bug. SP's own pathfinder, when it cannot
// reach the target, walks to the CLOSEST reachable tile instead ("the original
// target is moved to the closest reachable tile" -- Pathfinder.java). So a pin
// standing on a bank booth or inside a doorway routes perfectly well; the
// player just stops next to it. What hurts is a pin whose nearest standable
// tile is far away, or has none at all: those are the ZMI / gnome bank /
// Fishing Contest shape, where SP silently drew nothing.
const SLACK = 5;
const near = stranded.filter((s) => s.anchor && s.anchor[3] <= SLACK);
const misplaced = stranded.filter((s) => s.anchor && s.anchor[3] > SLACK);
const dead = stranded.filter((s) => !s.anchor);

// A broken pin only costs a play session if the GUIDE can route to it. Names
// that appear nowhere in the step text or location tags are seeding leftovers,
// and mixing them in is what turns a review list back into a dump.
const guide = fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8').toLowerCase();
const inGuide = (name) => guide.includes(`"${name}`) || guide.includes(` ${name}`)
  || guide.includes(`>${name}`);
for (const s of stranded) s.used = inGuide(s.name);

const show = (rows, title, note) => {
  console.log(`\n=== ${title} (${rows.length}) ===`);
  if (note) console.log(note);
  const listed = showAll ? rows : rows.filter((s) => s.used);
  for (const s of listed) {
    console.log(`  ${s.used ? '*' : ' '} ${s.name.padEnd(36)} ${String(s.pin.x).padStart(4)},${s.pin.y}`
      + ` p${s.pin.plane || 0}  (${s.label})`
      + (s.anchor ? `  -> re-anchor ${s.anchor[0]},${s.anchor[1]} p${s.anchor[2]}`
        + ` (${s.anchor[3]} tiles)` : ''));
  }
  const hidden = rows.length - listed.length;
  if (hidden) console.log(`  (${hidden} more the guide never names -- seeding leftovers; --all to see)`);
};

show(misplaced, 'WRONG SPOT -- right area, but SP stops well short',
  'The pin is not standable and the nearest tile that is sits further than SP\'s own\n'
  + 'closest-tile fallback should have to cover. Re-anchor at the suggested tile.');
show(dead, 'NO ROUTE -- SP has no transport into this place at all',
  'Nothing standable within 120 tiles. Either the pin is inside an instance, or it is\n'
  + 'content SP does not model yet. A step routing here draws nothing and says nothing.');
if (showAll) {
  show(near, `FINE -- unstandable but within ${SLACK} tiles of a routable tile`,
    'SP re-targets to the closest reachable tile, so these route correctly today.');
} else {
  console.log(`\n${near.length} pins sit on an object or in a doorway but within ${SLACK} tiles`
    + ` of a routable tile\n(SP re-targets to the closest reachable tile, so these are fine) -- --all to list them.`);
}
console.log(`\n${misplaced.length} wrong spot  |  ${dead.length} no route  |  `
  + `${near.length} fine-with-slack  |  ${fine.length} directly reachable`);
if (showAll) {
  console.log('\n=== reachable pins ===');
  for (const f of fine) {
    console.log(`  ${f.name.padEnd(36)} ${f.pin.x},${f.pin.y} p${f.pin.plane || 0}`
      + (f.slack ? `  (${f.slack} tiles off the nearest standable tile)` : ''));
  }
}
