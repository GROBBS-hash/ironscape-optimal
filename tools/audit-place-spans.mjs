#!/usr/bin/env node
// Which place names SWALLOW another place name -- and so silently stop an
// NPC being outlined?
//
// Written after the Lady of the Lake was never outlined on her own step.
// Two causes stacked; this tool is for the second, which nothing could
// have found by reading code:
//
//   places.json held BOTH "lady of the lake" and "lady of the lake in
//   taverly", at IDENTICAL coordinates. The second was a pure alias.
//
// It matters because of one rule in the plugin. An NPC name sitting inside
// a LONGER place name is read as the place talking, not the NPC -- that is
// what stops "Barbarian" lighting up every barbarian in the step "Walk to
// Barbarian Village" (IronscapePlugin.insideLongerSpan, which suppresses
// only a STRICTLY longer span; an equal-length one is the NPC itself and
// stays). So the alias spanned her name, and she was suppressed on the one
// step that names her.
//
// The failure is invisible: no warning, no log line, just an NPC that is
// never highlighted. Any such pair does it, so the pairs are worth finding
// before a play session does.
//
//   node tools/audit-place-spans.mjs
//   node tools/audit-place-spans.mjs --places <path>   # audit another file
//
// The --places switch exists so this can be run against an older revision
// and shown to reproduce a case that has already been fixed:
//
//   git show 23557d3:src/main/resources/com/ironscape/places/places.json > /tmp/old.json
//   node tools/audit-place-spans.mjs --places /tmp/old.json
//
// which is how it was checked -- a tool nobody has seen find a real bug is
// not evidence of anything.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');

const arg = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : fallback;
};

const placesPath = arg('--places', path.join(RES, 'places/places.json'));
const places = JSON.parse(fs.readFileSync(placesPath, 'utf8')).places;

// ---- mirror PlaceManager ------------------------------------------------
// rebuildPattern() builds the alternation from DISPLAYS, not keys, sorted
// LONGEST FIRST so "Romeo & Juliet" beats "Romeo". Because Java (and JS)
// alternation is leftmost-first, one scan therefore returns the LONGEST
// name matching at each position -- which is precisely how an alias hides
// the name inside it.
const tolerantPattern = (name) => {
  let out = '';
  for (const c of name) {
    if (/[a-zA-Z0-9 ]/.test(c)) out += c;
    else if (c === "'" || c === '’') out += "['’]";
    else if (c === '&') out += '(?:&|&amp;)';
    else out += c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
  return out;
};

const entries = Object.entries(places).map(([key, p]) => ({
  key, display: p.display ?? key, x: p.x, y: p.y, plane: p.plane ?? 0, type: p.type ?? null,
}));

const byLongestDisplay = [...entries].sort((a, b) => b.display.length - a.display.length);
const namePattern = new RegExp(
  '\\b(?:' + byLongestDisplay.map((e) => tolerantPattern(e.display)).join('|') + ')\\b', 'gi');

const placeSpans = (text) => {
  const spans = [];
  namePattern.lastIndex = 0;
  let m;
  while ((m = namePattern.exec(text)) !== null) {
    spans.push({ start: m.index, end: m.index + m[0].length, text: m[0] });
    if (m[0].length === 0) namePattern.lastIndex++;
  }
  return spans;
};

// Word-bounded containment of one display inside another.
const containsName = (outer, inner) => {
  const re = new RegExp('\\b' + tolerantPattern(inner) + '\\b', 'i');
  return outer.toLowerCase() !== inner.toLowerCase() && re.test(outer);
};

const dist = (a, b) => (a.plane !== b.plane
  ? Infinity
  : Math.round(Math.hypot(a.x - b.x, a.y - b.y)));

// ---- 1. redundant aliases ----------------------------------------------
// The first cut of this check was "one display contains another, at the
// same spot", and it was WRONG -- it reported 40-odd pairs that are all
// fine. "Desert Treasure I" contains "Desert Treasure"; "Ceril Carnillean"
// contains "Ceril". Nothing is hidden in either case, because the longer
// string is the entity's own fuller name, so a matching NPC matches it at
// full length and the equal-length rule keeps it.
//
// What actually went wrong with the Lady is narrower and has a shape: the
// longer entry was her name plus a LOCATIONAL QUALIFIER -- "Lady of the
// lake IN TAVERLY". That is a sentence fragment someone seeded to make a
// step's whole phrase clickable, not the name of anything, and it is the
// only kind of alias that can hide a real name.
//
// Quest and transport pins are excluded on both sides: no NPC is called
// "Desert Treasure II", so nothing they span can be suppressed.
const QUALIFIER = /^(?:in|at|near|outside|inside|by|behind|under|west|east|north|south)\b/i;
const SAME_SPOT = 5;
const isNameable = (e) => e.type !== 'quest' && e.type !== 'transport';

const redundant = [];
for (const outer of entries.filter(isNameable)) {
  for (const inner of entries.filter(isNameable)) {
    if (outer === inner || !containsName(outer.display, inner.display)) continue;
    // What does the outer name add on top of the inner one?
    const extra = outer.display.toLowerCase()
      .replace(inner.display.toLowerCase(), ' ')
      .replace(/\s+/g, ' ').trim();
    if (!QUALIFIER.test(extra)) continue;   // a fuller NAME, not a phrase
    const d = dist(outer, inner);
    if (d <= SAME_SPOT) redundant.push({ outer, inner, d, extra });
  }
}

console.log('=== REDUNDANT ALIAS — a longer pin sitting on top of a shorter one ===');
if (redundant.length === 0) {
  console.log('none\n');
} else {
  for (const r of redundant) {
    console.log(`  "${r.outer.display}"  (${r.outer.x},${r.outer.y})`);
    console.log(`    is "${r.inner.display}" plus "${r.extra}" — a phrase, not a name`);
    console.log(`    both pins ${r.d} tiles apart; delete the key "${r.outer.key}"`);
    console.log(`    -> while it exists, an NPC named "${r.inner.display}" is never outlined`);
  }
  console.log('');
}

// ---- 2. suppression in the guide's own text -----------------------------
// The pair above is the certain case. This is the general one: anywhere a
// span the plugin actually produces strictly contains another place name,
// an NPC of that name goes dark on that step -- even when the two pins are
// legitimately far apart.
const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const sid = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);

const findings = [];
const counts = new Map();
let idx = 0;
for (const ch of guide.chapters) {
  for (const sec of ch.sections) {
    for (const st of sec.steps) {
      const text = (st.content || []).map((c) => c.text).join('');
      let id = sid(text);
      const seen = (counts.get(id) || 0) + 1; counts.set(id, seen);
      if (seen > 1) id = `${id}-${seen}`;
      for (const span of placeSpans(text)) {
        const outerEntry = entries.find(
          (e) => e.display.toLowerCase() === span.text.toLowerCase());
        if (!outerEntry) continue;
        for (const e of entries.filter(isNameable)) {
          if (!containsName(span.text, e.display)) continue;
          // Same discrimination as section 1: only a locational phrase
          // hides a name. A fuller proper name does not.
          const extra = span.text.toLowerCase()
            .replace(e.display.toLowerCase(), ' ')
            .replace(/\s+/g, ' ').trim();
          if (!QUALIFIER.test(extra)) continue;
          // ...and the two must be the SAME PLACE. Without this the check
          // reported "Varrock east bank hides Varrock" on four steps: a
          // real containment, but the bank is not the town and no NPC is
          // called Varrock, so nothing was ever suppressed. Suppression
          // only misleads when the longer name is an alias for the shorter.
          if (dist(outerEntry, e) > SAME_SPOT) continue;
          findings.push({ idx, id, text, outer: span.text, inner: e.display });
        }
      }
      idx++;
    }
  }
}

console.log('=== SUPPRESSED IN GUIDE TEXT — a matched span hides a shorter place name ===');
if (findings.length === 0) {
  console.log('none');
} else {
  for (const f of findings) {
    console.log(`  #${f.idx}  ${f.id}`);
    console.log(`     "${f.text.slice(0, 88)}"`);
    console.log(`     "${f.outer}" hides "${f.inner}" -> an NPC named "${f.inner}" is not outlined here`);
  }
}
console.log(`\n${redundant.length} redundant alias(es), ${findings.length} suppression site(s) in guide text.`);
