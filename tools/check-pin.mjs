// Can Shortest Path reach this tile? Ask BEFORE writing a pin down.
//
// audit-pin-reachability answers this for every pin we already ship. This
// answers it for a coordinate you are about to commit, which is the step that
// was missing: the Tempoross fix (wave 31) moved a pin from an island to a
// rope ladder, and the only way to know the ladder was routable was to check
// it. Guessing a "nearby" tile from a wiki map and shipping it is how a broken
// pin becomes a differently broken pin.
//
//   node tools/check-pin.mjs 3135,2840
//   node tools/check-pin.mjs 3135,2840,0 2440,3089
//   node tools/check-pin.mjs --pins            (every bundled pin, one line each)
//
// Prints, for each tile: whether SP can stand there, how far the nearest tile
// it CAN stand on is, and where that tile is.
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { loadMap } from './lib/sp-map.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');

const args = process.argv.slice(2);
const map = await loadMap({ quiet: false });

function verdict(x, y, z) {
  if (map.reachable(x, y, z)) return 'REACHABLE — SP can stand on this tile';
  const near = map.reachableNear(x, y, z, 5);
  if (near) {
    return `fine — not standable, but SP re-targets ${near[2]} tile(s) away`
      + ` to ${near[0]},${near[1]}`;
  }
  const anchor = map.nearestReachable(x, y, z);
  if (!anchor) return 'NO ROUTE — nothing standable within 120 tiles';
  return `WRONG SPOT — nearest standable tile is ${anchor[3]} tiles away`
    + ` at ${anchor[0]},${anchor[1]} p${anchor[2]}`;
}

if (args.includes('--pins')) {
  const read = (p) => JSON.parse(fs.readFileSync(path.join(RES, p), 'utf8'));
  for (const [label, set] of Object.entries({
    places: read('places/places.json').places,
    item_sources: read('places/item_sources.json').places,
  })) {
    for (const [name, pin] of Object.entries(set)) {
      if (!pin || typeof pin.x !== 'number' || pin.type === 'transport') continue;
      console.log(`${name.padEnd(40)} ${pin.x},${pin.y} p${pin.plane || 0} (${label})`
        + `  ${verdict(pin.x, pin.y, pin.plane || 0)}`);
    }
  }
} else if (!args.length) {
  console.log('usage: node tools/check-pin.mjs x,y[,plane] [x,y[,plane] ...]');
} else {
  for (const arg of args) {
    const [x, y, z] = arg.split(',').map(Number);
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      console.log(`${arg.padEnd(20)} not a coordinate`);
      continue;
    }
    console.log(`${arg.padEnd(20)} ${verdict(x, y, Number.isFinite(z) ? z : 0)}`);
  }
}
