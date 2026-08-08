// Which of our pins can Shortest Path actually REACH?
//
// SP walks the collision map and crosses gaps only where it has a
// transport — a door, ladder, cave entrance, boat, fairy ring. Anywhere
// off the surface is therefore reachable only if SP knows a transport
// that lands there. When it doesn't, SP draws NOTHING and says NOTHING:
// no error, no marker, no log line. From the player's seat that is
// indistinguishable from the plugin being broken, and it has cost three
// separate play sessions (ZMI wave 5, the gnome banks wave 10, the
// Fishing Contest pin up White Wolf Mountain in wave 13).
//
// So ask SP's own data. It publishes every transport it knows as TSVs;
// this pulls all of them and checks each off-surface pin for a transport
// endpoint nearby. No endpoint in range = SP cannot get there = the pin
// needs re-anchoring at the routable approach point, which is the fix
// ZMI, Brimstail and the gnome banks all needed.
//
//   node tools/audit-pin-reachability.mjs [--all] [--radius N]
//
// --all also lists the off-surface pins that ARE reachable.
// Cached under tools/.sp-cache; delete it to re-download.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const CACHE = path.join(__dirname, '.sp-cache');
const REPO = 'https://raw.githubusercontent.com/Skretzo/shortest-path/master/'
  + 'src/main/resources/transports/';
const API = 'https://api.github.com/repos/Skretzo/shortest-path/contents/'
  + 'src/main/resources/transports';

const args = process.argv.slice(2);
const showAll = args.includes('--all');
const RADIUS = args.indexOf('--radius') >= 0
  ? Number(args[args.indexOf('--radius') + 1]) : 40;

// The surface sits below y=4000; dungeons are offset far above it. Same
// band test the plugin uses in firstLegTowards (SURFACE_MAX_Y).
const SURFACE_MAX_Y = 4000;
const offSurface = (p) => p.y > SURFACE_MAX_Y || (p.plane || 0) > 0;

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

const listing = JSON.parse(await cached('_listing.json', API));
const files = listing.filter((f) => f.name.endsWith('.tsv')).map((f) => f.name);

// Every coordinate SP knows as a transport endpoint. Both ends count: an
// ORIGIN inside a dungeon means SP can stand there just as much as a
// destination does.
const endpoints = [];
const COORD = /^(\d+)\s+(\d+)\s+(\d+)$/;
for (const name of files) {
  const tsv = await cached(name, REPO + name);
  for (const line of tsv.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue;
    const cells = line.split('\t');
    for (const cell of [cells[0], cells[1]]) {
      const m = cell && cell.trim().match(COORD);
      if (m) {
        endpoints.push({ x: +m[1], y: +m[2], plane: +m[3] });
      }
    }
  }
}

// Bucket by plane and a coarse grid so the lookup isn't 725 x 200,000.
const CELL = 64;
const grid = new Map();
const key = (x, y, plane) => `${plane}:${Math.floor(x / CELL)}:${Math.floor(y / CELL)}`;
for (const e of endpoints) {
  const k = key(e.x, e.y, e.plane);
  if (!grid.has(k)) grid.set(k, []);
  grid.get(k).push(e);
}

function nearestEndpoint(pin) {
  const plane = pin.plane || 0;
  let best = null;
  const gx = Math.floor(pin.x / CELL);
  const gy = Math.floor(pin.y / CELL);
  const span = Math.ceil(RADIUS / CELL) + 1;
  for (let dx = -span; dx <= span; dx++) {
    for (let dy = -span; dy <= span; dy++) {
      for (const e of grid.get(`${plane}:${gx + dx}:${gy + dy}`) ?? []) {
        const d = Math.round(Math.hypot(e.x - pin.x, e.y - pin.y));
        if (!best || d < best.d) best = { d, e };
      }
    }
  }
  return best;
}

const read = (p) => JSON.parse(fs.readFileSync(path.join(RES, p), 'utf8'));
const sets = {
  places: read('places/places.json').places,
  item_sources: read('places/item_sources.json').places,
};

const unreachable = [];
const reachable = [];
let surface = 0;

for (const [label, map] of Object.entries(sets)) {
  for (const [name, pin] of Object.entries(map)) {
    if (!pin || typeof pin.x !== 'number') continue;
    if (!offSurface(pin)) { surface++; continue; }
    const near = nearestEndpoint(pin);
    const row = { label, name, pin, near };
    if (!near || near.d > RADIUS) unreachable.push(row); else reachable.push(row);
  }
}

// Distance to the nearest transport is a PROXY, not proof: once SP is
// underground it can walk, so a pin 60 tiles from a ladder may be fine
// while one on a plane SP never enters cannot be. Tiered so the two
// aren't reported as the same claim.
const tierOf = (r) => {
  if (!r.near) return 'DEFINITE';   // SP never sets foot on this plane
  if (r.near.d > 100) return 'LIKELY';
  return 'BORDERLINE';              // walkable from the transport, maybe
};
unreachable.sort((a, b) => (b.near ? b.near.d : 1e9) - (a.near ? a.near.d : 1e9));

console.log(`Shortest Path transport data: ${files.length} files, `
  + `${endpoints.length} endpoints\n`);
console.log(`=== OFF-SURFACE pins with NO transport endpoint within ${RADIUS} tiles ===`);
console.log('DEFINITE   = SP has no transport on that plane at all; it cannot go there.');
console.log('LIKELY     = nearest transport >100 tiles; almost certainly a different area.');
console.log('BORDERLINE = a transport is close-ish; SP may reach it by walking underground.\n');
for (const r of unreachable) {
  console.log(`  ${tierOf(r).padEnd(11)} ${r.name.padEnd(38)} ${r.pin.x},${r.pin.y} p${r.pin.plane || 0}`
    + (r.near ? `   nearest transport ${r.near.d} tiles` : '   nothing on this plane'));
}
console.log(`\n${unreachable.length} unreachable`
  + `  |  ${reachable.length} off-surface but reachable`
  + `  |  ${surface} on the surface (not checked)`);

if (showAll) {
  console.log('\n=== off-surface pins SP CAN reach ===');
  for (const r of reachable.sort((a, b) => a.near.d - b.near.d)) {
    console.log(`  ${r.name.padEnd(38)} ${r.pin.x},${r.pin.y} p${r.pin.plane || 0}`
      + `   transport ${r.near.d} tiles away`);
  }
}
