// Shortest Path's own collision map and transport list, loaded once.
//
// Extracted from audit-pin-reachability so more than one tool can ask the same
// question. The audit asks it of every bundled pin; check-pin.mjs asks it of a
// coordinate you are about to write down, which is the difference between
// fixing a pin and moving it somewhere else that does not work either.
//
// Cached under tools/.sp-cache (gitignored); delete it to pick up SP's latest.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CACHE = path.join(__dirname, '../.sp-cache');
const COLLISION = path.join(CACHE, 'collision');
const RAW = 'https://raw.githubusercontent.com/Skretzo/shortest-path/master/src/main/resources/';
const API = 'https://api.github.com/repos/Skretzo/shortest-path/contents/src/main/resources/transports';

const REGION = 64;
const PLANES = 4;
const TILES = REGION * REGION;

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

export async function loadMap({ quiet = false } = {}) {
  const log = quiet ? () => {} : (m) => console.log(m);

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

  // One region = 64x64 tiles, 2 bits per tile per plane, planes stacked. Bit
  // layout from SP's SplitFlagMap. Regions are sparse -- most of the coordinate
  // space is empty sea -- so index by region rather than allocating the world.
  const regionBuf = new Map();
  let regionCount = 0;
  const regionIndex = new Map();
  for (const name of fs.readdirSync(COLLISION)) {
    const [rx, ry] = name.split('_').map(Number);
    const key = rx * 1000 + ry;
    regionBuf.set(key, fs.readFileSync(path.join(COLLISION, name)));
    regionIndex.set(key, regionCount++);
  }
  log(`collision map: ${regionCount} regions`);

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
  log(`transports: ${transportCount} crossings on the map\n`);

  const total = regionCount * PLANES * TILES;
  const seen = new Uint8Array(total);
  const START = [3222, 3218, 0];                      // Lumbridge, where SP starts everyone
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
  log(`Shortest Path can stand on ${visited.toLocaleString()} tiles reached from Lumbridge\n`);

  const reachable = (x, y, z = 0) => {
    const i = id(x, y, z);
    return i >= 0 && !!seen[i];
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

  return { reachable, reachableNear, nearestReachable, visited, regionCount };
}
