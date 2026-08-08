// Where did the first-leg hint's straight line lie?
//
// firstLegTowards used to rank teleport landings by straight-line distance, and
// a straight line cannot see a mountain, a wall, a river or a coast. This joins
// the bundled travel table against every pin the guide can route to and reports
// the targets where the two metrics DISAGREE about which landing to offer.
//
// Each disagreement is a hint the plugin used to get wrong. The exemplar is
// P1-08: Keep Le Faye, where Burthorpe Games Room won on a straight line and
// the walk turns out to run over White Wolf Mountain.
//
//   node tools/audit-first-legs.mjs [--all] [--limit N]
//
// --all also lists the targets both metrics agree on.
// Needs src/main/resources/com/ironscape/travel/travel_distances.bin.gz, which
// tools/build-travel-distances.mjs writes.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const args = process.argv.slice(2);
const showAll = args.includes('--all');
const LIMIT = args.indexOf('--limit') >= 0 ? Number(args[args.indexOf('--limit') + 1]) : 40;

// ------------------------------------------------------------ the table
const raw = zlib.gunzipSync(fs.readFileSync(path.join(RES, 'travel/travel_distances.bin.gz')));
if (raw.toString('ascii', 0, 4) !== 'IRTD') throw new Error('not a travel distance table');
const CELL = raw.readInt32LE(8);
const MINX = raw.readInt32LE(12);
const MINY = raw.readInt32LE(16);
const CW = raw.readInt16LE(20);
const CH = raw.readInt16LE(22);
let off = 24;
const count = raw.readInt32LE(off); off += 4;
const table = new Map();
for (let i = 0; i < count; i++) {
  const len = raw.readInt32LE(off); off += 4;
  const name = raw.toString('utf8', off, off + len); off += len;
  const cells = new Uint16Array(CW * CH);
  for (let c = 0; c < cells.length; c++) { cells[c] = raw.readUInt16LE(off); off += 2; }
  table.set(name, cells);
}
const cellOf = (x, y) => {
  const cx = Math.floor((x - MINX) / CELL), cy = Math.floor((y - MINY) / CELL);
  return (x < MINX || y < MINY || cx >= CW || cy >= CH) ? -1 : cy * CW + cx;
};

// The landings firstLegTowards can actually propose. Spirit trees are in the
// table as network stops, not as candidates, so they are excluded here.
const landings = JSON.parse(fs.readFileSync(path.join(RES, 'places/minigame_landings.json'), 'utf8')).landings;
const CANDIDATES = Object.entries(landings).map(([n, p]) => [n, p.x, p.y]);
CANDIDATES.push(
  ['Varrock Teleport', 3213, 3424], ['Lumbridge Teleport', 3222, 3218],
  ['Falador Teleport', 2965, 3379], ['Camelot Teleport', 2757, 3479],
  ['Ardougne Teleport', 2662, 3305], ['Watchtower Teleport', 2547, 3113]);

// ------------------------------------------------------------ the targets
const places = JSON.parse(fs.readFileSync(path.join(RES, 'places/places.json'), 'utf8')).places;
const sources = JSON.parse(fs.readFileSync(path.join(RES, 'places/item_sources.json'), 'utf8')).places;

const cheb = (ax, ay, bx, by) => Math.max(Math.abs(ax - bx), Math.abs(ay - by));
const rows = [];
for (const [label, set] of [['place', places], ['source', sources]]) {
  for (const [name, p] of Object.entries(set)) {
    if (!p || typeof p.x !== 'number' || p.type === 'transport') continue;
    // Off-surface targets are already refused by the plugin's band rule.
    if (p.y > 4000 || (p.plane || 0) > 0) continue;
    const cell = cellOf(p.x, p.y);
    if (cell < 0) continue;
    let byLine = null, byWalk = null;
    for (const [n, cx, cy] of CANDIDATES) {
      const straight = cheb(cx, cy, p.x, p.y);
      if (!byLine || straight < byLine[1]) byLine = [n, straight];
      const field = table.get(n);
      const walked = field ? field[cell] : 65535;
      if (walked !== 65535 && (!byWalk || walked < byWalk[1])) byWalk = [n, walked];
    }
    if (!byLine || !byWalk) continue;
    // What the straight line claimed for the landing it chose, against what
    // walking it really costs. That gap IS the lie.
    const chosenWalk = table.get(byLine[0])?.[cell];
    rows.push({
      label, name, p,
      line: byLine, walk: byWalk,
      lieBy: chosenWalk === undefined || chosenWalk === 65535
        ? Infinity : chosenWalk - byWalk[1],
      unreachable: chosenWalk === 65535,
    });
  }
}

const disagree = rows.filter((r) => r.line[0] !== r.walk[0]).sort((a, b) => b.lieBy - a.lieBy);
console.log(`${rows.length} routable surface pins checked against ${CANDIDATES.length} landings\n`);
console.log('=== targets where the straight line picked the WRONG landing ===');
console.log('"cost" is the extra walking the straight line\'s pick would have added.\n');
for (const r of disagree.slice(0, LIMIT)) {
  console.log(`  +${(r.unreachable ? 'no route' : r.lieBy).toString().padStart(8)}  ${r.name.padEnd(34)}`
    + ` ${String(r.p.x).padStart(4)},${r.p.y}`);
  console.log(`  ${' '.repeat(10)}  straight line: ${r.line[0]} (${r.line[1]} tiles away)`);
  console.log(`  ${' '.repeat(10)}  walking:       ${r.walk[0]} (${r.walk[1]} tiles to walk)`);
}
if (disagree.length > LIMIT) console.log(`  ... and ${disagree.length - LIMIT} more (--limit N)`);
console.log(`\n${disagree.length} of ${rows.length} targets got the wrong landing`
  + `  |  ${rows.length - disagree.length} agreed`);
const stranded = disagree.filter((r) => r.unreachable);
if (stranded.length) {
  console.log(`\n${stranded.length} of those had NO ungated walking route from the landing the`
    + ` straight line chose. Read "ungated" literally: the table counts only crossings`
    + `\nany account can make, so a Mort'ton landing reads as stranded from Varrock even`
    + `\nthough Priest in Peril opens that walk. Either way the landing loses, which is`
    + `\nthe right answer for the account that has not done the quest and no loss for the`
    + `\none that has, since a nearer landing always won those.`);
  for (const r of stranded.slice(0, 12)) console.log(`  ${r.name.padEnd(34)} <- ${r.line[0]}`);
}
if (showAll) {
  console.log('\n=== targets both metrics agree on ===');
  for (const r of rows.filter((x) => x.line[0] === x.walk[0])) {
    console.log(`  ${r.name.padEnd(34)} ${r.walk[0]} (${r.walk[1]} tiles)`);
  }
}
