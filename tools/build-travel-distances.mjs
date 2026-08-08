// Precompute TRAVEL distance from each teleport landing to everywhere on the
// surface, so the first-leg hint can stop ranking candidates by straight line.
//
// The bug this exists for (P1-08): the hint offered a Burthorpe Games Room
// teleport toward Keep Le Faye because Burthorpe is ~145 tiles away in a
// straight line against ~240 for the Fishing Trawler landing. But Keep Le Faye
// is on the far side of White Wolf Mountain. Walking it is 531 tiles, which
// makes Burthorpe the EIGHTH best landing, not the third. Euclidean distance
// cannot see a mountain, and the same shape covers the Ardougne wall, every
// river, and every island.
//
// Path distance lives in Shortest Path's pathfinder and the Plugin Hub forbids
// reaching across classloaders to it. But SP publishes the DATA the pathfinder
// runs on, and that we can read offline:
//   collision-map.zip   one bit per tile edge: can I step north, can I step east
//   transports/*.tsv    every crossing it knows (doors, gates, stairs, boats)
// So we do SP's job once, here, at full tile resolution, and ship the answers.
//
// What ships is a distance FIELD per landing, downsampled to 32-tile cells:
// 92x52 cells x 19 landings x 2 bytes = 178KB raw, 39KB gzipped. The distances
// themselves are computed at full resolution; only the QUERY point is rounded,
// which costs ~7% on the real distribution of our own pins.
//
//   node tools/build-travel-distances.mjs [--check]
//
// --check recomputes and reports without writing. Cached under tools/.sp-cache
// (gitignored); delete it to pick up SP's latest map.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const CACHE = path.join(__dirname, '.sp-cache');
const COLLISION = path.join(CACHE, 'collision');
const RAW = 'https://raw.githubusercontent.com/Skretzo/shortest-path/master/src/main/resources/';
const OUT = path.join(RES, 'travel/travel_distances.bin.gz');

const checkOnly = process.argv.includes('--check');

// The surface. Everything off it (dungeons at y+6400, the rune essence mine at
// y~4830) is parked far north on the map, and the plugin already refuses to
// compare across that band — see SURFACE_MAX_Y in IronscapePlugin.
const MINX = 1024, MAXX = 3968, MINY = 2496, MAXY = 4160;
const CELL = 32;
const GW = MAXX - MINX, GH = MAXY - MINY;
const CW = Math.ceil(GW / CELL), CH = Math.ceil(GH / CELL);
const UNREACHABLE = 65535;
const tid = (x, y) => (y - MINY) * GW + (x - MINX);

// ---------------------------------------------------------------- collision

async function ensureCollision() {
  fs.mkdirSync(CACHE, { recursive: true });
  const zip = path.join(CACHE, 'collision-map.zip');
  if (!fs.existsSync(zip)) {
    console.log('downloading collision-map.zip from Shortest Path...');
    const res = await fetch(RAW + 'collision-map.zip', { headers: { 'User-Agent': 'ironscape-dev tooling' } });
    if (!res.ok) throw new Error(`${res.status} fetching collision-map.zip`);
    fs.writeFileSync(zip, Buffer.from(await res.arrayBuffer()));
  }
  if (!fs.existsSync(COLLISION)) {
    // No unzip in the Node stdlib; PowerShell ships with Windows and the
    // entries are plain files named "<regionX>_<regionY>".
    execFileSync('powershell', ['-NoProfile', '-Command',
      `Expand-Archive -LiteralPath '${zip}' -DestinationPath '${COLLISION}' -Force`]);
  }
}
await ensureCollision();

// One region = 64x64 tiles, 2 bits per tile, planes stacked. Bit layout copied
// from SP's SplitFlagMap: (z*64*64 + (y&63)*64 + (x&63))*2 + flag, flag 0 =
// "can step north from here", flag 1 = "can step east from here". We only read
// plane 0; upstairs pins get answered by their ground-level cell, which is what
// a 32-tile grid can honestly resolve anyway.
const REGION = 64;
const regions = new Map();
for (const name of fs.readdirSync(COLLISION)) {
  regions.set(name.replace('_', ','), fs.readFileSync(path.join(COLLISION, name)));
}
function flag(x, y, f) {
  const buf = regions.get(`${Math.floor(x / REGION)},${Math.floor(y / REGION)}`);
  if (!buf) return 0;
  const bit = ((y & 63) * REGION + (x & 63)) * 2 + f;
  return (buf[bit >> 3] >> (bit & 7)) & 1;
}
const canN = (x, y) => flag(x, y, 0);
const canS = (x, y) => flag(x, y - 1, 0);
const canE = (x, y) => flag(x, y, 1);
const canW = (x, y) => flag(x - 1, y, 1);
const walkable = (x, y) => canN(x, y) || canS(x, y) || canE(x, y) || canW(x, y);

// ---------------------------------------------------------------- transports

// Long-distance NETWORKS never go in. Choosing between them is the hint's whole
// job, so baking them into the map it reasons on would let it argue with
// itself: every landing would read as "close to everything".
const NETWORKS = /^(teleportation_|fairy_rings|gnome_gliders|spirit_trees|magic_carpets|magic_mushtrees|quetzal|minecarts|hot_air_balloons|wilderness_obelisks|seasonal_transports|charter_ships|canoes|agility_shortcuts)/;

// What is left is ordinary crossings, and we keep only the UNGATED ones — no
// quest, no skill, no item (coins excepted; a 30gp boat is not a gate for
// anyone this guide is written for). Two reasons. One: we cannot know the
// account's unlocks at build time. Two: every error then lands in the safe
// direction. A missing shortcut makes a journey read LONGER than it is, which
// makes the hint suggest less, and over-suggesting is the failure this whole
// item is about. Measured against the permissive alternative, the reported case
// ranks 8th here and 5th there — both fix the bug, and this one cannot invent a
// gate the player has not opened.
const COORD = /^(\d+)\s+(\d+)\s+(\d+)$/;
async function loadTransports() {
  const listing = JSON.parse(fs.readFileSync(path.join(CACHE, '_listing.json'), 'utf8'));
  const edges = new Map();
  let kept = 0, seen = 0;
  for (const entry of listing.filter((f) => f.name.endsWith('.tsv'))) {
    if (NETWORKS.test(entry.name)) continue;
    const file = path.join(CACHE, entry.name);
    if (!fs.existsSync(file)) {
      const res = await fetch(RAW + 'transports/' + entry.name, { headers: { 'User-Agent': 'ironscape-dev tooling' } });
      fs.writeFileSync(file, await res.text());
    }
    const tsv = fs.readFileSync(file, 'utf8');
    const head = tsv.split(/\r?\n/)[0].split('\t');
    const qCol = head.findIndex((h) => /Quest/i.test(h));
    const sCol = head.findIndex((h) => /Skill/i.test(h));
    const iCol = head.findIndex((h) => /Item/i.test(h));
    const dCol = head.findIndex((h) => /Duration/i.test(h));
    for (const line of tsv.split(/\r?\n/)) {
      if (!line || line.startsWith('#')) continue;
      const c = line.split('\t');
      const a = c[0]?.trim().match(COORD);
      const b = c[1]?.trim().match(COORD);
      if (!a || !b) continue;
      seen++;
      if (qCol >= 0 && c[qCol]?.trim()) continue;
      if (sCol >= 0 && c[sCol]?.trim()) continue;
      if (iCol >= 0 && c[iCol]?.trim() && !/COINS=/.test(c[iCol])) continue;
      if (+a[3] || +b[3]) continue;                       // plane 0 only
      const ox = +a[1], oy = +a[2], dx = +b[1], dy = +b[2];
      if (ox < MINX || ox >= MAXX || oy < MINY || oy >= MAXY) continue;
      if (dx < MINX || dx >= MAXX || dy < MINY || dy >= MAXY) continue;
      // SP costs a transport by its Duration in ticks and a walked tile by 1,
      // so a duration-9 boat is worth nine tiles of walking. Same here.
      const w = Math.max(1, dCol >= 0 && c[dCol] ? Number(c[dCol]) || 1 : 1);
      const k = tid(ox, oy);
      if (!edges.has(k)) edges.set(k, []);
      edges.get(k).push([tid(dx, dy), w]);
      kept++;
    }
  }
  console.log(`transports: ${kept} ungated surface crossings kept of ${seen} read`);
  return edges;
}
const transports = await loadTransports();

// ---------------------------------------------------------------- the search

// Dijkstra with a bucket queue: weights are small integers, so "the next
// bucket" is the whole priority queue and the search stays linear.
function distanceField(sx, sy) {
  const dist = new Int32Array(GW * GH).fill(-1);
  const buckets = [];
  const push = (i, d) => { (buckets[d] ||= []).push(i); };
  dist[tid(sx, sy)] = 0;
  push(tid(sx, sy), 0);
  for (let d = 0; d < buckets.length; d++) {
    const bucket = buckets[d];
    if (!bucket) continue;
    for (const cur of bucket) {
      if (dist[cur] !== d) continue;                      // superseded entry
      const y = MINY + ((cur / GW) | 0), x = MINX + (cur % GW);
      const relax = (nx, ny, nd) => {
        if (nx < MINX || nx >= MAXX || ny < MINY || ny >= MAXY) return;
        const i = tid(nx, ny);
        if (dist[i] < 0 || dist[i] > nd) { dist[i] = nd; push(i, nd); }
      };
      if (canN(x, y)) relax(x, y + 1, d + 1);
      if (canS(x, y)) relax(x, y - 1, d + 1);
      if (canE(x, y)) relax(x + 1, y, d + 1);
      if (canW(x, y)) relax(x - 1, y, d + 1);
      for (const [to, w] of transports.get(cur) ?? []) {
        if (dist[to] < 0 || dist[to] > d + w) { dist[to] = d + w; push(to, d + w); }
      }
    }
  }
  return dist;
}

// A landing's published coordinate can sit on an object rather than a floor
// tile; walk out until we find somewhere you could actually stand.
function snap(x, y) {
  for (let r = 0; r <= 20; r++) {
    for (let dx = -r; dx <= r; dx++) {
      for (let dy = -r; dy <= r; dy++) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
        if (walkable(x + dx, y + dy)) return [x + dx, y + dy];
      }
    }
  }
  return null;
}

// Downsample to CELL-tile cells, keeping the nearest tile in each. The cell's
// best case is the honest reading for "how far is that area", and it errs
// optimistic by ~7% on our own pins — small against the hint's 40% margin.
function downsample(dist) {
  const out = new Uint16Array(CW * CH).fill(UNREACHABLE);
  for (let y = MINY; y < MAXY; y++) {
    const row = (((y - MINY) / CELL) | 0) * CW;
    for (let x = MINX; x < MAXX; x++) {
      const d = dist[tid(x, y)];
      if (d < 0) continue;
      const i = row + (((x - MINX) / CELL) | 0);
      if (d < out[i]) out[i] = Math.min(d, UNREACHABLE - 1);
    }
  }
  return out;
}

// ---------------------------------------------------------------- origins

// Every point firstLegTowards can propose. Names must match the plugin's own:
// the minigames come from minigame_landings.json, the spells and the free home
// teleport from TELEPORT_SPELLS / HOME_TELEPORT_LANDING in IronscapePlugin.
// TravelDistancesTest fails the build if the two ever drift apart.
const landings = JSON.parse(fs.readFileSync(path.join(RES, 'places/minigame_landings.json'), 'utf8')).landings;
const origins = [];
for (const [name, p] of Object.entries(landings)) origins.push([name, p.x, p.y]);
const SPELLS = [
  ['Varrock Teleport', 3213, 3424],
  ['Lumbridge Teleport', 3222, 3218],
  ['Falador Teleport', 2965, 3379],
  ['Camelot Teleport', 2757, 3479],
  ['Ardougne Teleport', 2662, 3305],
  ['Watchtower Teleport', 2547, 3113],
];
for (const s of SPELLS) origins.push(s);
origins.push(['Home Teleport', 3222, 3218]);
// The five permanent spirit trees. effectiveDistance already lets a landing
// near any tree reach the tree nearest the target, and that shortcut has to
// survive the move to travel distances — which needs the trees as origins, not
// just as targets. Prefix matches SPIRIT_TREE_ORIGINS in IronscapePlugin.
const SPIRIT_TREES = [
  ['Spirit Tree: Tree Gnome Village', 2542, 3170],
  ['Spirit Tree: Gnome Stronghold', 2461, 3444],
  ['Spirit Tree: Battlefield of Khazard', 2555, 3259],
  ['Spirit Tree: Grand Exchange', 3183, 3508],
  ['Spirit Tree: Feldip Hills', 2488, 2850],
];
for (const t of SPIRIT_TREES) origins.push(t);

const fields = [];
for (const [name, x, y] of origins) {
  const s = snap(x, y);
  if (!s) throw new Error(`${name} ${x},${y} has no walkable tile within 20 tiles`);
  const started = Date.now();
  const small = downsample(distanceField(...s));
  let reachable = 0;
  for (const v of small) if (v !== UNREACHABLE) reachable++;
  fields.push([name, small]);
  console.log(`  ${name.padEnd(24)} ${String(x).padStart(4)},${y}`
    + `${s[0] === x && s[1] === y ? '     ' : ` ->${s[0]},${s[1]}`}`
    + `  ${String(reachable).padStart(4)}/${CW * CH} cells  ${Date.now() - started}ms`);
}

// ---------------------------------------------------------------- write

// Header, then each field's name and its CW*CH little-endian uint16s.
const header = Buffer.alloc(24);
header.write('IRTD', 0, 'ascii');                          // magic
header.writeInt32LE(1, 4);                                 // format version
header.writeInt32LE(CELL, 8);
header.writeInt32LE(MINX, 12);
header.writeInt32LE(MINY, 16);
header.writeInt16LE(CW, 20);
header.writeInt16LE(CH, 22);
const parts = [header, Buffer.from(Int32Array.of(fields.length).buffer)];
for (const [name, data] of fields) {
  const nameBuf = Buffer.from(name, 'utf8');
  const len = Buffer.alloc(4);
  len.writeInt32LE(nameBuf.length, 0);
  parts.push(len, nameBuf, Buffer.from(data.buffer.slice(0)));
}
const packed = zlib.gzipSync(Buffer.concat(parts), { level: 9 });

console.log(`\n${fields.length} fields, grid ${CW}x${CH} @ ${CELL} tiles`
  + ` -> ${(packed.length / 1024).toFixed(0)}KB gzipped`);

if (checkOnly) {
  console.log('--check: nothing written');
} else {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, packed);
  console.log(`wrote ${path.relative(path.join(__dirname, '..'), OUT)}`);
}

// A few answers we can check against the game rather than against ourselves.
const cellOf = (x, y) => (((y - MINY) / CELL) | 0) * CW + (((x - MINX) / CELL) | 0);
const byName = new Map(fields);
console.log('\nsanity checks (travel distance in tiles):');
for (const [from, to, place] of [
  ['Falador Teleport', [3013, 3355], 'Falador east bank'],
  ['Varrock Teleport', [3164, 3487], 'Grand Exchange'],
  ['Camelot Teleport', [2725, 3491], "Seers' Village bank"],
  ['Castle Wars', [2613, 3093], 'Yanille bank'],
  ['Burthorpe Games Room', [2757, 3401], 'Keep Le Faye  <- the P1-08 case'],
  ['Fishing Trawler', [2757, 3401], 'Keep Le Faye'],
]) {
  const v = byName.get(from)[cellOf(...to)];
  console.log(`  ${from.padEnd(22)} -> ${place.padEnd(28)} ${v === UNREACHABLE ? 'unreachable' : v}`);
}
