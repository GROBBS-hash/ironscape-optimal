#!/usr/bin/env node
// What is going to annoy you in the next stretch of the route?
//
// Written after a play session that went slowly because every problem was
// met for the first time in the field: a step that could never auto-tick,
// a kit that could never justify a bank stop, a chain leg that had been
// erased. None of those needed a game to find. They were all sitting in
// dumps this repo already writes -- nobody had ever asked them about the
// steps that were about to happen.
//
// So: read where the player actually IS (the plugin persists the route
// position in the RuneLite profile) and report what the next N steps can
// and cannot do. Every finding here is something you would otherwise
// discover by standing in front of it.
//
//   node tools/preflight.mjs                  # next 15 from your position
//   node tools/preflight.mjs --steps 40
//   node tools/preflight.mjs --position 300
//   node tools/preflight.mjs --all            # the whole guide, summary only
//
// Needs build/completion-paths.tsv and build/arrival-audit.tsv:
//   gradlew test --tests "*.GoalAuditDumpTest"
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const BUILD = path.join(__dirname, '../build');

const arg = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : fallback;
};
const showAll = process.argv.includes('--all');

// ---- where the player is -------------------------------------------------
// The plugin persists it per profile; the profile that HAS the key is the
// one being played. No argument needed, which is the point -- a check you
// have to configure is a check nobody runs.
function positionFromProfile() {
  const dir = path.join(os.homedir(), '.runelite', 'profiles2');
  let best = null;
  let files = [];
  try {
    files = fs.readdirSync(dir).filter((f) => f.endsWith('.properties'));
  } catch {
    return null;
  }
  for (const f of files) {
    const body = fs.readFileSync(path.join(dir, f), 'utf8');
    const m = body.match(/^ironscape\.position_OZIRIS=(\d+)/m);
    if (!m) continue;
    const mtime = fs.statSync(path.join(dir, f)).mtimeMs;
    if (!best || mtime > best.mtime) {
      best = { profile: f.replace(/-\d+\.properties$/, ''), position: +m[1], mtime };
    }
  }
  return best;
}

// ---- the dumps -----------------------------------------------------------
const pathsFile = path.join(BUILD, 'completion-paths.tsv');
if (!fs.existsSync(pathsFile)) {
  console.error('build/completion-paths.tsv missing -- run:\n'
    + '  gradlew test --tests "*.GoalAuditDumpTest"');
  process.exit(1);
}
const steps = [];
for (const line of fs.readFileSync(pathsFile, 'utf8').split(/\r?\n/)) {
  const c = line.split('\t');
  if (c[0] === 'PATH' && c[1]) steps.push({ sub: c[1], id: c[1].split(':')[0], path: c[2], text: c[3] || '' });
}

const arrival = new Map();
const arrivalFile = path.join(BUILD, 'arrival-audit.tsv');
if (fs.existsSync(arrivalFile)) {
  for (const line of fs.readFileSync(arrivalFile, 'utf8').split(/\r?\n/)) {
    const c = line.split('\t');
    if (c[0] === 'ARRIVAL' && c[1]) arrival.set(c[1], { tier: c[2], place: c[3] });
  }
}

const ann = JSON.parse(fs.readFileSync(
  path.join(RES, 'annotations/annotations_oziris.json'), 'utf8')).annotations;
const annFor = (step) => [ann[step.sub], ann[step.id]].filter(Boolean);

// A quest step routes to the quest's GIVER, which is neither an annotation
// nor an arrival place -- the first run of this tool called eight of twelve
// steps unroutable for missing exactly that, and a check nobody believes is
// worse than no check.
const givers = Object.keys(JSON.parse(fs.readFileSync(
  path.join(RES, 'places/quest_givers.json'), 'utf8')).givers);
// ... and a step naming any known PLACE routes there via firstPlaceIn, which
// is the other half of targetFor's chain. Apostrophes are normalised away:
// the guide writes "Black knight's fortress", the giver key is "black
// knights' fortress", and that alone was reported as a missing route.
const places = Object.entries(JSON.parse(fs.readFileSync(
  path.join(RES, 'places/places.json'), 'utf8')).places)
  .filter(([, v]) => v.type !== 'transport')
  .map(([k, v]) => (v.display || k));
const norm = (s) => s.toLowerCase().replace(/['’]/g, '').replace(/\s+/g, ' ');
const nameable = [...givers, ...places]
  .map(norm)
  .filter((n) => n.length >= 4);
const namesSomewhere = (text) => {
  const t = norm(text);
  return nameable.some((n) => t.includes(n));
};

// ... and the last of targetFor's sources: the step's own 📍 LOCATION tag,
// which lives in the guide payload rather than in any dump. Missing it made
// this check cry "no route" over most of the opening stretch -- steps tagged
// "Lumbridge Castle" or "Ferox Enclave" that the plugin routes perfectly
// well. Third over-report from this one check; each time the cause was a
// source of targetFor's that the tool did not know about.
const allPlaces = JSON.parse(fs.readFileSync(
  path.join(RES, 'places/places.json'), 'utf8')).places;
const placeKeys = new Set(Object.keys(allPlaces).map((k) => k.toLowerCase()));

// Mirrors IronscapePlugin.isBareDestination: a step that is nothing but a
// place -- "Lumby", "Varrock east bank" -- names where to be without using
// a movement verb. Quest and transport entries share the place namespace
// and are excluded, or the bare steps "Cabin fever" and "One small favour"
// would read as destinations. Both sides decide that from the SAME type
// field in places.json, so they cannot disagree about which names count.
// NB: uses PlaceManager.key()'s normalisation, not this file's norm() --
// norm() drops apostrophes and the plugin keeps them, so sharing it would
// silently disagree about every place with one in its name.
const placeKey = (s) => s.toLowerCase().trim().replace(/’/g, "'").replace(/&amp;/g, '&');
const isBareDestination = (text) => {
  const t = placeKey(text).replace(/^(?:the|to|at|in|then|and)\s+/, '').trim();
  const place = allPlaces[t];
  return !!place && place.type !== 'quest' && place.type !== 'transport';
};
// getLoose strips a directional prefix before giving up, so "North of
// Ardougne" routes to Ardougne.
const resolvesAsPlace = (name) => {
  if (!name) return false;
  const n = name.toLowerCase().trim();
  if (placeKeys.has(n)) return true;
  const stripped = n.replace(/^(?:north|south|east|west)(?:[ -](?:east|west))?\s+of\s+/, '');
  return stripped !== n && placeKeys.has(stripped);
};

const locationByStep = new Map();
{
  const guide = JSON.parse(fs.readFileSync(
    path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
  const runText = (rs) => (rs || []).map((r) => r.text).join('');
  const sid = (t) => crypto.createHash('sha256')
    .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
  for (const ch of guide.chapters) {
    for (const sec of ch.sections) {
      for (const st of sec.steps) {
        locationByStep.set(sid(runText(st.content)), st.metadata?.location ?? null);
      }
    }
  }
}

// The movement verbs that make a sub a travel INSTRUCTION rather than an
// action that merely happens somewhere -- the reason 'Run south to Port
// sarim' ticks itself.
//
// Read from the plugin's OWN pattern (GoalAuditDumpTest dumps it) rather
// than hand-copied here. A copy is right until someone edits one side,
// and a check that then calls a step tickable when the plugin cannot tick
// it is worse than no check at all.
const MOVEMENT = (() => {
  const dumped = path.join(BUILD, 'movement-word.txt');
  if (!fs.existsSync(dumped)) {
    console.error(
      'preflight: build/movement-word.txt is missing, so arrival ticks cannot be\n'
      + '           judged. Run:  gradlew test --tests "*.GoalAuditDumpTest"');
    process.exit(1);
  }
  // Java and JS share this regex dialect; \b, (?:), and | all mean the same.
  return new RegExp(fs.readFileSync(dumped, 'utf8').trim() + '|spirit tree', 'i');
})();

// ---- the checks ----------------------------------------------------------
function inspect(step) {
  const entries = annFor(step);
  const flags = [];
  const notes = [];

  const errands = entries.find((e) => e.errands)?.errands;
  const hasCheckpoint = entries.some((e) => e.requires || e.requiresAll);
  const items = entries.flatMap((e) => e.items || []);
  const gating = items.filter((i) => !i.optional && !i.granted && !i.ingredient);
  const target = entries.find((e) => e.target && !e.target.cleared)?.target;

  // 1. CAN IT EVER TICK? A step with no detector, no checkpoint annotation
  //    and no errand chain can only ever be ticked by hand. Plenty are
  //    legitimately advice ("bank everything"), but meeting one unwarned
  //    reads as a bug every single time.
  // ARRIVAL has no goal behind it, so "no goal" is NOT "cannot tick": "Run
  // south to Port sarim" has no detector and still completes when you get
  // there. It needs a movement instruction and somewhere resolvable, the
  // pair currentSubSatisfied tests. Missing this overstated the count and
  // would have put a wrong label on the step.
  const arr0 = arrival.get(step.sub);
  // Mirrors IronscapePlugin.TALK_INSTRUCTION: arriving is not talking, so a
  // sub asking for a conversation cannot be finished by walking up to it.
  const wantsConversation = /\b(?:speak|talk|ask)\b/i.test(step.text);
  const canArrive = !wantsConversation
    && ((arr0 && arr0.tier !== 'NONE')
      || ((MOVEMENT.test(step.text) || isBareDestination(step.text))
          && (!!target || namesSomewhere(step.text))));
  const canAutoTick = step.path !== 'none' || hasCheckpoint || !!errands || canArrive;
  if (!canAutoTick) flags.push('MANUAL ONLY      no detector, no checkpoint, no chain, no arrival');
  else if (step.path === 'none' && canArrive && !hasCheckpoint && !errands) {
    notes.push('ticks on arrival (no detector, but it is a movement instruction)');
  }
  else if (step.path === 'none' && errands) notes.push(`ticks when its chain completes (${errands.length} legs)`);
  else if (step.path === 'none' && hasCheckpoint) notes.push('ticks off a varbit/varp checkpoint');

  // 2. IS THERE ANYWHERE TO GO? No pin, no chain, and no place the arrival
  //    audit could resolve means the Go button and auto-nav have nothing.
  const arr = arrival.get(step.sub);
  const routable = !!target || !!errands || (arr && arr.tier !== 'NONE')
    || namesSomewhere(step.text) || resolvesAsPlace(locationByStep.get(step.id));
  if (!routable && !/^(bank|buy|sell|train|get \d)/i.test(step.text)) {
    flags.push('NO ROUTE         no pin, no chain, no resolvable place');
  }

  // 3. WILL A BANK STOP EVER FIRE? bankFirstTarget skips unnumbered items
  //    on purpose -- they are the site's running carry advice, not
  //    requirements -- so a kit made ENTIRELY of them is invisible to it.
  //    Not a defect. Worth knowing before you assume banking is handled.
  if (gating.length && gating.every((i) => i.quantity == null)) {
    flags.push(`CARRY-LIST KIT   ${gating.length} items, none numbered — no bank stop can fire`);
  }

  // 4. Quest steps: we now stand down for Quest Helper entirely.
  if (/quest-(start|finish)/.test(step.path)) {
    notes.push('quest step — Quest Helper owns guidance, our route stands down');
  }
  return { flags, notes, errands, gating };
}

// ---- report --------------------------------------------------------------
const where = positionFromProfile();
const position = +arg('--position', where ? where.position : 0);
const count = +arg('--steps', 15);
const window = showAll ? steps : steps.slice(position, position + count);

if (!showAll) {
  console.log(`IRONSCAPE PRE-FLIGHT — ${window.length} steps from position ${position}`
    + (where && !process.argv.includes('--position') ? `  (profile "${where.profile}")` : ''));
  console.log('Everything below is something you would otherwise find by standing in front of it.\n');
}

const totals = { manual: 0, noRoute: 0, carryKit: 0, quest: 0 };
for (let i = 0; i < window.length; i++) {
  const step = window[i];
  const { flags, notes } = inspect(step);
  for (const f of flags) {
    if (f.startsWith('MANUAL')) totals.manual++;
    if (f.startsWith('NO ROUTE')) totals.noRoute++;
    if (f.startsWith('CARRY-LIST')) totals.carryKit++;
  }
  if (notes.some((n) => n.startsWith('quest step'))) totals.quest++;
  if (showAll || (!flags.length && !notes.length)) continue;
  const n = showAll ? i : position + i;
  console.log(`${String(n).padStart(4)}  ${step.id}  ${step.text.slice(0, 68)}`);
  for (const f of flags) console.log(`        ${f}`);
  for (const nt of notes) console.log(`        ok: ${nt}`);
}

const scope = showAll ? `all ${steps.length} steps` : `these ${window.length} steps`;
console.log(`\nSUMMARY over ${scope}:`);
console.log(`  ${totals.manual} can only be ticked BY HAND`);
console.log(`  ${totals.noRoute} have nowhere to route`);
console.log(`  ${totals.carryKit} carry a kit no bank stop can act on`);
console.log(`  ${totals.quest} hand guidance to Quest Helper`);
