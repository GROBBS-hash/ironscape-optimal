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
import { execFileSync } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const CACHE = path.join(__dirname, '.sp-cache');
const COLLISION = path.join(CACHE, 'collision');
const RAW = 'https://raw.githubusercontent.com/Skretzo/shortest-path/master/src/main/resources/';
const API = 'https://api.github.com/repos/Skretzo/shortest-path/contents/src/main/resources/transports';

const showAll = process.argv.includes('--all');

async function cached(name, url) {
  fs.mkdirSync(CACHE, { recursive: true });
  const file = path.join(CACHE, name);
  if (fs.existsSync(file)) return fs.readFileSync(file, 'utf8');
  const res = await fetch(url, { headers: { 'User-Agent': 'ironscape-dev tooling' } });
  if (!res.ok) throw new Error(`${res.status} for ${url}`);
  const text = await res.text();
  fs.writeFileSync(file, text);
  return text;
}

const zip = path.join(CACHE, 'collision-map.zip');
if (!fs.existsSync(zip)) {
  fs.mkdirSync(CACHE, { recursive: true });
  const res = await fetch(RAW + 'collision-map.zip', { headers: { 'User-Agent': 'ironscape-dev tooling' } });
  if (!res.ok) throw new Error(`${res.status} fetching collision-map.zip`);
  fs.writeFileSync(zip, Buffer.from(await res.arrayBuffer()));
}
if (!fs.existsSync(COLLISION)) {
  execFileSync('powershell', ['-NoProfile', '-Command',
    `Expand-Archive -LiteralPath '${zip}' -DestinationPath '${COLLISION}' -Force`]);
}

// ------------------------------------------------------------ the map
// One region = 64x64 tiles, 2 bits per tile per plane, planes stacked. Bit
// layout from SP's SplitFlagMap. Regions are sparse -- most of the coordinate
// space is empty sea -- so index by region rather than allocating the world.
const REGION = 64;
const PLANES = 4;
const TILES = REGION * REGION;
const regionBuf = new Map();
let regionCount = 0;
const regionIndex = new Map();
for (const name of fs.readdirSync(COLLISION)) {
  const [rx, ry] = name.split('_').map(Number);
  const key = rx * 1000 + ry;
  regionBuf.set(key, fs.readFileSync(path.join(COLLISION, name)));
  regionIndex.set(key, regionCount++);
}
console.log(`collision map: ${regionCount} regions`);

const regionKey = (x, y) => Math.floor(x / REGION) * 1000 + Math.floor(y / REGION);
function flag(x, y, z, f) {
  const buf = regionBuf.get(regionKey(x, y));
  if (!buf) return 0;
  if (z < 0 || z >= buf.length / 1024) return 0;
  const bit = (z * TILES + (y & 63) * REGION + (x & 63)) * 2 + f;
  return (buf[bit >> 3] >> (bit & 7)) & 1;
}
const canN = (x, y, z) => flag(x, y, z, 0);
const canS = (x, y, z) => flag(x, y - 1, z, 0);
const canE = (x, y, z) => flag(x, y, z, 1);
const canW = (x, y, z) => flag(x - 1, y, z, 1);
const walkable = (x, y, z) => canN(x, y, z) || canS(x, y, z) || canE(x, y, z) || canW(x, y, z);

// Dense id over the regions that exist, so a visited bitset costs megabytes
// rather than hundreds.
function id(x, y, z) {
  const r = regionIndex.get(regionKey(x, y));
  if (r === undefined || z < 0 || z >= PLANES) return -1;
  return ((r * PLANES + z) * TILES) + (y & 63) * REGION + (x & 63);
}
const reverse = new Int32Array(regionCount);
for (const [key, index] of regionIndex) reverse[index] = key;
function unpack(i) {
  const local = i % TILES;
  const rest = (i - local) / TILES;
  const z = rest % PLANES;
  const key = reverse[(rest - z) / PLANES];
  return [Math.floor(key / 1000) * REGION + (local % REGION),
    (key % 1000) * REGION + Math.floor(local / REGION), z];
}

// ------------------------------------------------------------ transports
// EVERY transport, gated or not. The question here is whether SP can draw a
// path at all for a player who qualifies -- not whether a fresh account can
// walk it. A pin that is unreachable even with fairy rings and quest doors
// open is unreachable, full stop.
const listing = JSON.parse(await cached('_listing.json', API));
const COORD = /^(\d+)\s+(\d+)\s+(\d+)$/;
const links = new Map();
let transportCount = 0;
for (const entry of listing.filter((f) => f.name.endsWith('.tsv'))) {
  const tsv = await cached(entry.name, RAW + 'transports/' + entry.name);
  for (const line of tsv.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue;
    const c = line.split('\t');
    const a = c[0]?.trim().match(COORD);
    const b = c[1]?.trim().match(COORD);
    if (!a || !b) continue;
    const from = id(+a[1], +a[2], +a[3]);
    const to = id(+b[1], +b[2], +b[3]);
    if (from < 0 || to < 0) continue;
    if (!links.has(from)) links.set(from, []);
    links.get(from).push(to);
    transportCount++;
  }
}
console.log(`transports: ${transportCount} crossings on the map\n`);

// ------------------------------------------------------------ flood fill
const total = regionCount * PLANES * TILES;
const seen = new Uint8Array(total);
const START = [3222, 3218, 0];                        // Lumbridge, where SP starts everyone
const queue = [];
const push = (i) => {
  if (i < 0 || seen[i]) return;
  seen[i] = 1;
  queue.push(i);
};
push(id(...START));
let visited = 0;
for (let head = 0; head < queue.length; head++) {
  const cur = queue[head];
  visited++;
  const [x, y, z] = unpack(cur);
  if (canN(x, y, z)) push(id(x, y + 1, z));
  if (canS(x, y, z)) push(id(x, y - 1, z));
  if (canE(x, y, z)) push(id(x + 1, y, z));
  if (canW(x, y, z)) push(id(x - 1, y, z));
  for (const to of links.get(cur) ?? []) push(to);
}
console.log(`Shortest Path can stand on ${visited.toLocaleString()} tiles reached from Lumbridge\n`);

// ------------------------------------------------------------ the pins
const read = (p) => JSON.parse(fs.readFileSync(path.join(RES, p), 'utf8'));
const sets = {
  places: read('places/places.json').places,
  item_sources: read('places/item_sources.json').places,
};

// A pin can legitimately sit ON an object -- a bank booth, a door -- so accept
// any reachable tile touching it before calling it stranded.
function reachableNear(x, y, z, radius) {
  for (let r = 0; r <= radius; r++) {
    for (let dx = -r; dx <= r; dx++) {
      for (let dy = -r; dy <= r; dy++) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
        const i = id(x + dx, y + dy, z);
        if (i >= 0 && seen[i]) return [x + dx, y + dy, r];
      }
    }
  }
  return null;
}

// For a stranded pin, the nearest tile SP CAN reach -- the re-anchor point.
function nearestReachable(x, y, z) {
  let best = null;
  for (let r = 1; r <= 120 && !best; r += 1) {
    for (let dx = -r; dx <= r && !best; dx++) {
      for (let dy = -r; dy <= r; dy++) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
        for (const plane of [z, 0, 1, 2, 3]) {
          const i = id(x + dx, y + dy, plane);
          if (i >= 0 && seen[i]) { best = [x + dx, y + dy, plane, r]; break; }
        }
        if (best) break;
      }
    }
  }
  return best;
}

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
