// Shared analysis for the ⌖ pin / nearest-NPC audit.
//
// THE DEFECT THIS MEASURES. A ⌖ target nominates the nearest NPC within
// 4 tiles of the pin: that NPC gets the outline and the step's wanted
// item floats over their head. The pin is only meant to do that when it
// marks a PERSON — a shopkeeper, a quest giver. When it marks a furnace,
// a dig spot or a staircase, whoever happens to stand there gets crowned
// (wave 28: a level-2 Man wearing a sickle icon at the Al Kharid
// furnace). `"npc": false` on the pin turns the nomination off.
//
// WHY THIS IS NOT A BULK FIX. Setting the flag on a pin that really does
// mark a person removes an outline SILENTLY — nobody notices a missing
// highlight, so a wrong answer in that direction survives forever. So
// this file classifies and shortlists; the owner settles the rest.
//
// THE POPULATION IS MUCH SMALLER THAN THE PIN COUNT, and that is the
// main thing this file works out. The nearest-NPC fallback in
// IronscapePlugin only runs when the step names NOBODY:
//
//   - an active errand chain takes the anchor over entirely (the sub's
//     own ⌖ never anchors while a chain is driving),
//   - a seeded shop keeper for the step is added by name,
//   - a seeded quest giver for the step's quest is added by name,
//   - any NPC name matched in the step's own text,
//   - an object-grind sub (a stall, ore rocks) opts out,
//
// and in every one of those cases `npcNames` is non-empty, which is the
// exact condition the fallback is guarded on. A pin on a step like that
// can never crown a bystander no matter what it marks.
//
// HARD vs SOFT suppression. An errand chain, a seeded shop keeper and a
// seeded quest giver are all readable from the data, so those pins are
// provably safe today. A NAME in the step's text is a guess at what the
// live matcher will find in the scene, so it is reported separately.
//
// AND THAT NAME TEST HAS ONE TRAP, WHICH THE FIRST VERSION WALKED INTO:
// matching every places.json key called 56 pins suppressed, 35 of them
// on the strength of "lumby", "Draynor", "Yanille" — PLACE names, which
// the live code goes out of its way to exclude from NPC matching (that
// is what placeSpans and insideLongerSpan are for, so that "Walk to
// Barbarian Village" does not outline every Barbarian). Only curated
// PERSON names count here: shop keepers, quest givers, item-source
// vendors and errand NPCs.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';

/**
 * The player's OWN captured pins. AnnotationManager.getTarget reads local
 * FIRST, so a captured pin completely replaces the bundled one — flag
 * included. Writing npc:false into the bundle therefore does nothing on
 * a machine that has captured that step, which is the machine the fix
 * gets tested on. Absent on any other machine, which is fine: the file
 * is evidence about this install, not about the guide.
 */
export function localTargets() {
  const file = path.join(os.homedir(), '.runelite/ironscape/annotations.json');
  if (!fs.existsSync(file)) {
    return new Map();
  }
  try {
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    const entries = data.annotations || data;
    return new Map(Object.entries(entries)
      .filter(([, v]) => v && v.target && v.target.cleared !== true)
      .map(([k, v]) => [k, v.target]));
  } catch (e) {
    return new Map();
  }
}

/** Step ids are a hash of the step's normalised text (see GuideManifest). */
export const stepIdOf = (text) => crypto.createHash('sha256')
  .update(text.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8')
  .digest('hex').slice(0, 10);

// What the step sends you to STAND AT. These are deliberately phrases,
// not bare words: the first cut matched "fire" and called "Buy a fire
// staff" a furnace pin, when the pin is Zaff's shop counter and the
// nomination there is exactly right. A word that is half of an item name
// is not evidence about a place.
const PLACE_PATTERNS = [
  [/\bdig\b/, 'a dig spot'],
  [/\bfurnace\b/, 'a furnace'],
  [/\banvil\b|\bsmith \d|\bsmith [a-z]+ (wire|tips|bars?|arrow)/, 'an anvil'],
  [/\bsmelt\b/, 'a furnace'],
  [/\bmine \d|\bmine \w+ ore\b|\bquarry\b/, 'ore rocks'],
  [/^bank$|\bbank them\b|\bbank it\b/, 'a bank'],
  [/\bsafespot\b/, 'a safespot tile'],
  [/\bfish \w+|\bfishing spot\b/, 'a fishing spot'],
  [/\bagility course\b|\blaps?\b/, 'an agility course'],
  [/\bon the fire\b|\bburn (it|them)\b/, 'a fire'],
  [/\bcompost bin\b|\bbin\b/, 'a compost bin'],
  [/\bfill \d+ (buckets?|inventory)|\bbuckets? with water\b/, 'a water source'],
  [/\bmolten glass\b|\bglassblow/, 'a furnace'],
  [/\b(chronicle|cloak|carpet|glider|home) tele/, 'a teleport landing'],
  [/^use chronicle tele$|^chronicle tele$/, 'a teleport landing'],
  [/\bspinning wheel\b|\bloom\b|\bpotter/, 'a crafting object'],
  [/\bstairs?\b|\bladder\b|\btrapdoor\b|\bstaircase\b/, 'a way up or down'],
  [/\bcrate\b|\bbarrel\b/, 'a crate'],
  [/\baltar\b/, 'an altar'],
];

// What the step sends you to TALK TO. A pin next to a person, on a step
// about that person, is the case nomination was built for.
const PERSON_PATTERNS = [
  [/\btalk to\b|\bspeak to\b|\bspeak with\b|\btalk with\b/, 'talks to someone'],
  [/\bbuy [^.]*\bfrom\b|\bsell [^.]*\bto\b|\btrade [^.]*\bwith\b/, 'buys from or sells to someone'],
  [/\bshop\b|\bstore\b|\bshopkeeper\b|\bseller\b|\bstall\b(?! )/, 'names a shop'],
  [/\bpickpocket\b|\bthieve from\b/, 'steals from someone'],
  [/\bgive [^.]*\bto\b|\bhand in\b/, 'hands something over'],
];

// Object-grind phrasing that already opts a sub out of the fallback.
const GRIND_WORDS = ['stall', 'rocks', 'pick ', 'chop ', 'fish ', 'mine '];

// Nav names that imply a counter with somebody behind it, so a pin on one
// is a person pin whatever the sentence says.
const SHOPFRONT = /\b(shop|store|stall|market|bar|inn|pub|tavern|bank)\b/;

const readRes = (root, rel) => JSON.parse(fs.readFileSync(
  path.join(root, 'src/main/resources/com/ironscape', rel), 'utf8'));

const plainText = (runs) => (runs || []).map((r) => r.text || '').join('');

/**
 * Reads every bundled ⌖ target and works out, for each, whether the
 * nearest-NPC nomination can reach it and whether the pin looks like a
 * person or a place.
 */
export function analyseTargets(root) {
  const guide = readRes(root, 'guide/guide_data_oziris.json');
  const annotations = readRes(root, 'annotations/annotations_oziris.json').annotations;
  const places = readRes(root, 'places/places.json').places;
  const sources = readRes(root, 'places/item_sources.json').places;
  const keepers = readRes(root, 'places/shop_npcs.json').keepers;
  const givers = readRes(root, 'places/quest_givers.json').givers;
  const local = localTargets();

  // Every step, by id, with its text, notes, position and metadata.
  //
  // The DUPLICATE SUFFIX IS REAL DATA, not corruption: GuideLoader
  // appends -2, -3 when the same sentence appears twice ("Chronicle
  // tele" is five separate steps). The first version of this file did
  // not know that and reported three live pins as stale keys — which
  // would have argued for deleting three correct annotations.
  const steps = new Map();
  const idCounts = new Map();
  let index = 0;
  for (const chapter of guide.chapters) {
    for (const section of chapter.sections) {
      for (const step of section.steps) {
        index += 1;
        const text = plainText(step.content);
        const notes = (step.additionalContent || []).map(plainText).join(' ');
        const base = stepIdOf(text);
        const seen = (idCounts.get(base) || 0) + 1;
        idCounts.set(base, seen);
        steps.set(seen === 1 ? base : `${base}-${seen}`, {
          index, text, notes, section: section.title,
          metadata: step.metadata || {},
        });
      }
    }
  }

  // Named pins we can compare a ⌖ against. A place entry with no type is
  // a nav name, which is as often an NPC ("Baraek") as a landmark, so
  // the name is offered as evidence rather than read as a verdict.
  const named = [];
  for (const [key, value] of Object.entries({ ...places, ...sources })) {
    if (value && typeof value.x === 'number') {
      named.push({ key, display: value.display || key, type: value.type || '', ...value });
    }
  }
  // Curated PERSON names — everyone we have ever written down as an NPC.
  // A name from here appearing in a step's text will match the scene NPC
  // and fill npcNames, which switches the nearest-pin fallback off.
  const personNames = new Set();
  for (const name of Object.values(keepers)) {
    personNames.add(name.toLowerCase());
  }
  for (const name of Object.values(givers)) {
    personNames.add(name.toLowerCase());
  }
  for (const value of Object.values(sources)) {
    if (typeof value.vendor === 'string') {
      personNames.add(value.vendor.toLowerCase());
    }
  }
  for (const entry of Object.values(annotations)) {
    for (const stage of entry.errands || []) {
      if (stage.npc) {
        personNames.add(stage.npc.toLowerCase());
      }
    }
  }

  const rows = [];
  for (const [key, entry] of Object.entries(annotations)) {
    if (!entry || !entry.target) {
      continue;
    }
    const target = entry.target;
    const stepId = key.includes(':') ? key.split(':')[0] : key;
    const step = steps.get(stepId);
    const text = step ? step.text : '(step not found — stale key)';
    const haystack = `${text} ${step ? step.notes : ''}`.toLowerCase();

    // --- can the nomination reach this pin at all? ---
    const blockers = [];
    if (entry.errands) {
      blockers.push('an errand chain drives this step — the sub\'s own ⌖ never anchors');
    }
    if (keepers[stepId] || keepers[key]) {
      blockers.push(`shop keeper seeded: ${keepers[stepId] || keepers[key]}`);
    }
    const quest = entry.quest || (step && step.metadata.quest);
    if (quest && givers[quest.toLowerCase()]) {
      blockers.push(`quest giver seeded: ${givers[quest.toLowerCase()]} (${quest})`);
    }
    // SOFT: a person we have written down elsewhere is named in this
    // step's text, so the live matcher will very likely find them in the
    // scene and the fallback never runs. Kept out of `blockers` because
    // it is a prediction about a scene we cannot see.
    const namedPeople = [...personNames]
      .filter((n) => n.length >= 4)
      .filter((n) => new RegExp(`\\b${n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`)
        .test(haystack));
    // Nav names in the text, as reading context only — these are mostly
    // PLACES and the live NPC scan excludes them on purpose.
    const namesInText = named
      .filter((p) => p.type !== 'quest' && p.key.length >= 4)
      .filter((p) => new RegExp(`\\b${p.key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`)
        .test(haystack))
      .map((p) => p.display);
    const grind = GRIND_WORDS.some((w) => haystack.includes(w));

    // --- what does the pin look like it marks? ---
    // The step's own sentence only — a NOTE talks about the wider job
    // ("Note: use phials to un-note planks") and kept dragging unrelated
    // words in.
    const sentence = text.toLowerCase();
    const placeHits = PLACE_PATTERNS.filter(([re]) => re.test(sentence)).map(([, what]) => what);
    const personHits = PERSON_PATTERNS.filter(([re]) => re.test(sentence)).map(([, what]) => what);

    // Nearby seeded pins, as evidence for the reader.
    const near = named
      .map((p) => ({
        ...p,
        distance: p.plane === target.plane
          ? Math.max(Math.abs(p.x - target.x), Math.abs(p.y - target.y)) : null,
      }))
      .filter((p) => p.distance !== null && p.distance <= 15)
      .sort((a, b) => a.distance - b.distance)
      .slice(0, 4);

    // A pin sitting ON a curated person, or on a shop front, is the case
    // the nomination exists for — within the same 4 tiles the live code
    // uses, so this asks the same question it does.
    const onPerson = near.filter((p) => p.distance <= 4
      && (personNames.has(p.key) || SHOPFRONT.test(p.key)));

    let verdict;
    if (target.npc === false) {
      verdict = 'already-flagged';
    } else if (blockers.length) {
      verdict = 'suppressed';
    } else if (namedPeople.length) {
      verdict = 'probably-suppressed';
    } else if (placeHits.length && !personHits.length && !onPerson.length) {
      verdict = 'likely-place';
    } else if ((personHits.length || onPerson.length) && !placeHits.length) {
      verdict = 'likely-person';
    } else {
      verdict = 'unclear';
    }

    rows.push({
      key, stepId, sub: key.includes(':'), verdict,
      index: step ? step.index : null,
      section: step ? step.section : null,
      text,
      note: entry.note || null,
      location: step ? step.metadata.location || null : null,
      quest: quest || null,
      x: target.x, y: target.y, plane: target.plane,
      safespot: target.safespot === true,
      npcFlag: target.npc === undefined ? null : target.npc,
      // A local capture on this key hides the bundled pin on this
      // install, so a bundled-only fix would be invisible here.
      shadowed: local.has(key) && local.get(key).npc !== false,
      blockers, grind, placeHits, personHits, namedPeople, namesInText: namesInText.slice(0, 4),
      onPerson: onPerson.map((p) => ({ display: p.display, distance: p.distance })),
      near: near.map((p) => ({ display: p.display, type: p.type, distance: p.distance })),
      items: (entry.items || []).map((i) => i.name).slice(0, 5),
    });
  }
  rows.sort((a, b) => (a.index || 0) - (b.index || 0));
  return rows;
}

/** Item sources share the pin shape: a vendor name, npc:false, or neither. */
export function analyseSources(root) {
  const sources = readRes(root, 'places/item_sources.json').places;
  return Object.entries(sources).map(([key, value]) => ({
    key,
    display: value.display || key,
    type: value.type || '',
    vendor: value.vendor || null,
    npcFlag: value.npc === undefined ? null : value.npc,
    note: value.note || null,
    x: value.x, y: value.y, plane: value.plane,
    // A transport network is skipped before nomination is even considered.
    settled: value.type === 'transport' || !!value.vendor || value.npc === false,
  }));
}

/** Decisions already settled, so a review page cannot re-ask them. */
export function readDecisions(root) {
  const file = path.join(root, 'tools/target-npc-reviewed.json');
  if (!fs.existsSync(file)) {
    return { reviewed: {} };
  }
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}
