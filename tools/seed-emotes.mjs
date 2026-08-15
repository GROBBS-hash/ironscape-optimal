#!/usr/bin/env node
/**
 * seed-emotes.mjs — WK-2. Which steps ask for an EMOTE, from Quest Helper.
 *
 * WHY. "Perform the Goblin Bow emote next to Mistag" leaves you scrolling
 * a list of eighty icons. EmoteHintOverlay points at the right one, and
 * it is confirmed working in play (wave 27) — so the remaining work was
 * always data: one step is seeded today and nobody has looked for the
 * rest.
 *
 * TWO STEP CLASSES, and missing one of them hides half the answer:
 * Quest Helper writes `new EmoteStep(...)` for an emote performed at a
 * PLACE and `new NpcEmoteStep(...)` for one performed at an NPC. The
 * first scan here only matched EmoteStep and reported nothing for The
 * Lost Tribe — the very quest already seeded by hand.
 *
 * THE CHECK THAT MATTERS MOST IS THE LAST ONE. Our overlay resolves an
 * emote NAME to a sprite through a hand-built map, and a name it does
 * not know simply never draws — no warning, no log line, a seeded
 * annotation that does nothing. So every proposed emote is checked
 * against the overlay's own map before anything is written, and an
 * unknown one is reported as needing a CODE change rather than quietly
 * seeded. Below Ice Mountain's FLEX is exactly that case.
 *
 * WHICH STEP OWNS THE EMOTE is the open question the backlog flagged.
 * Our steps are coarser than QH's: "Continue Lost tribe" spans far more
 * of the quest than the moment the emote is wanted. There is no signal
 * that says which of a quest's four steps is the right one, so this
 * seeds the FIRST step tagged with that quest and lets the click
 * stand-down do the rest — the hint may appear early, and one click
 * dismisses it for good (that flag persists, wave 27). Where a quest has
 * several DIFFERENT emotes, no step is seeded at all: picking one of
 * them arbitrarily would point at the wrong icon, which is worse than
 * pointing at nothing.
 *
 * Usage:
 *   node tools/seed-emotes.mjs           # report only
 *   node tools/seed-emotes.mjs --apply   # write the annotations
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { stepIdOf } from './lib/target-npc.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const RES = path.join(repo, 'src/main/resources/com/ironscape');
const CACHE = path.join(here, '.qh-cache');
const apply = process.argv.includes('--apply');

// ---- what the overlay can actually draw ---------------------------------
// Parsed from the overlay itself rather than copied, so this cannot drift
// from the thing doing the drawing.
const overlaySrc = fs.readFileSync(
  path.join(repo, 'src/main/java/com/ironscape/overlay/EmoteHintOverlay.java'), 'utf8');
const drawable = new Set([...overlaySrc.matchAll(/m\.put\("([a-z ]+)"/g)].map((m) => m[1]));

// ---- QuestEmote constant -> the name a player sees ----------------------
// QH's enum carries the display name; our annotations hold that name and
// the overlay matches it loosely (case and spacing ignored).
const questEmoteFile = path.join(CACHE, 'QuestEmote.java');
if (!fs.existsSync(questEmoteFile)) {
  const res = await fetch(
    'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/'
    + 'src/main/java/com/questhelper/steps/emote/QuestEmote.java',
    { headers: { 'User-Agent': 'ironscape-dev tooling' } });
  if (!res.ok) {
    console.error('Could not fetch QuestEmote.java — no name mapping, stopping.');
    process.exit(1);
  }
  fs.mkdirSync(CACHE, { recursive: true });
  fs.writeFileSync(questEmoteFile, await res.text());
}
const emoteNames = new Map();
for (const m of fs.readFileSync(questEmoteFile, 'utf8')
  .matchAll(/^\s*([A-Z][A-Z_0-9]*)\s*\(\s*"([^"]+)"/gm)) {
  emoteNames.set(m[1], m[2]);
}
if (!emoteNames.size) {
  console.error('QuestEmote.java parsed to zero names — the enum shape changed.');
  process.exit(1);
}

// ---- our steps, by quest ------------------------------------------------
const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annRoot = JSON.parse(fs.readFileSync(
  path.join(RES, 'annotations/annotations_oziris.json'), 'utf8'));
const ann = annRoot.annotations;

const plain = (runs) => (runs || []).map((r) => r.text || '').join('');
const steps = [];
const idCounts = new Map();
for (const ch of guide.chapters) {
  for (const sec of ch.sections) {
    for (const st of sec.steps) {
      const text = plain(st.content);
      const base = stepIdOf(text);
      const seen = (idCounts.get(base) || 0) + 1;
      idCounts.set(base, seen);
      steps.push({
        index: steps.length + 1,
        id: seen === 1 ? base : `${base}-${seen}`,
        text,
        quest: (st.metadata && st.metadata.quest) || null,
      });
    }
  }
}
// An annotation `quest` tag counts too: 8 steps whose task IS a quest leg
// carry no metadata tag at all (wave 19).
for (const s of steps) {
  if (!s.quest && ann[s.id] && ann[s.id].quest) {
    s.quest = ann[s.id].quest;
  }
}

const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
const stepsByQuest = new Map();
for (const s of steps) {
  if (!s.quest) {
    continue;
  }
  const k = norm(s.quest);
  if (!stepsByQuest.has(k)) {
    stepsByQuest.set(k, []);
  }
  stepsByQuest.get(k).push(s);
}

// ---- every emote Quest Helper asks for, from the cached sources ---------
const found = new Map();
for (const file of fs.readdirSync(CACHE)) {
  let src;
  try {
    src = fs.readFileSync(path.join(CACHE, file), 'utf8');
  } catch {
    continue;
  }
  if (!src.includes('EmoteStep(')) {
    continue;
  }
  const dir = file.match(/helpers_(?:quests|miniquests)_([a-z0-9]+)_/);
  if (!dir) {
    continue; // diaries and other helpers: not quest steps of ours
  }
  const emotes = [];
  for (const m of src.matchAll(
    /new (?:Npc)?EmoteStep\(\s*this,\s*(?:NpcID\.[A-Z_0-9]+,\s*)?QuestEmote\.([A-Z_0-9]+)([\s\S]{0,300}?)\)\s*;/g)) {
    const blurb = (m[2].match(/"([^"]{6,160})"/) || [])[1] || '';
    emotes.push({ constant: m[1], blurb });
  }
  if (emotes.length) {
    found.set(dir[1], emotes);
  }
}

// ---- join ---------------------------------------------------------------
const proposals = [];
const blocked = [];
const unmatched = [];
for (const [dir, emotes] of found) {
  const candidates = [...stepsByQuest.entries()]
    .filter(([k]) => k === dir || k.startsWith(dir) || dir.startsWith(k));
  const distinct = [...new Set(emotes.map((e) => e.constant))];
  if (!candidates.length) {
    unmatched.push({ dir, emotes: distinct });
    continue;
  }
  const questSteps = candidates[0][1];
  const step = questSteps[0];
  const constant = distinct[0];
  const name = emoteNames.get(constant);
  const row = {
    dir, constant, name, step,
    blurb: emotes.find((e) => e.constant === constant).blurb,
    stepCount: questSteps.length,
    ambiguous: distinct.length > 1,
    drawable: name ? drawable.has(norm(name)) : false,
    // ALREADY SEEDED ANYWHERE, not just on the step this tool would
    // pick. The Lost Tribe's Goblin bow sits on "Continue Lost tribe",
    // hand-placed at the moment the emote is actually wanted — and that
    // step carries no quest tag at all, so it is not even in this
    // quest's step list. Checking only the chosen step would have added
    // a SECOND copy on an earlier step, pointing at the right icon at
    // the wrong time. A hand placement beats this tool's first-step
    // guess every time.
    already: Object.values(ann).some((e) => e.emote
      && norm(e.emote) === norm(name || '')),
  };
  if (!row.name || !row.drawable || row.ambiguous) {
    blocked.push(row);
  } else {
    proposals.push(row);
  }
}

// ---- report -------------------------------------------------------------
console.log(`${found.size} quest(s) in Quest Helper ask for an emote.\n`);

for (const r of proposals) {
  console.log(`${r.already ? 'ALREADY' : 'SEED   '}  #${r.step.index} ${r.step.id}:0`);
  console.log(`          ${r.step.text.slice(0, 90)}`);
  console.log(`          emote "${r.name}" (${r.constant})`
    + `${r.stepCount > 1 ? `, quest spans ${r.stepCount} steps — seeded on the first` : ''}`);
  if (r.blurb) {
    console.log(`          QH: ${r.blurb.slice(0, 100)}`);
  }
  console.log('');
}

for (const r of blocked) {
  const why = !r.name ? `no display name for ${r.constant} in QuestEmote`
    : r.ambiguous ? `quest asks for ${'several different emotes'} — no way to say which step owns which`
      : `the overlay cannot draw "${r.name}" — it needs a sprite added in EmoteHintOverlay first`;
  console.log(`BLOCKED  #${r.step.index} ${r.step.text.slice(0, 70)}`);
  console.log(`          ${why}\n`);
}

for (const u of unmatched) {
  console.log(`(no step of ours: ${u.dir} — ${u.emotes.join(', ')})`);
}

// ---- apply --------------------------------------------------------------
if (!apply) {
  console.log('\nReport only. Re-run with --apply to write the seedable ones.');
} else {
  let written = 0;
  for (const r of proposals) {
    if (r.already) {
      continue;
    }
    const key = `${r.step.id}:0`;
    ann[key] = ann[key] || {};
    ann[key].emote = r.name;
    written += 1;
  }
  fs.writeFileSync(path.join(RES, 'annotations/annotations_oziris.json'),
    `${JSON.stringify(annRoot, null, 1)}\n`);
  console.log(`\n${written} emote annotation(s) written. Data only — ::ironreload.`);
}
