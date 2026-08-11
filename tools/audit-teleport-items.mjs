#!/usr/bin/env node
// Steps that TELL YOU TO TELEPORT without ever telling you to bring the
// thing that does it.
//
// Owner report, 2026-08-11, standing on step 264: the next step reads
// "Chronicle tele and start Dragon slayer at champion's guild" and nothing
// anywhere earlier says to carry a Chronicle. By the time you read it you
// have already left the bank, so the instruction is unfollowable exactly
// when it matters. Runes are the same shape: "use mind bomb and camelot
// tele" needs law and air runes in the bag.
//
// So: find every step whose text prescribes a means of travel that costs an
// ITEM, and report whether that item is in the step's own kit. What we do
// with the answer is a second question — listing it on the step itself at
// least makes it countable and bankable, and a note on the step before is
// what actually warns you in time.
//
//   node tools/audit-teleport-items.mjs            # the report
//   node tools/audit-teleport-items.mjs --json     # machine-readable
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');
const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const runText = (content) => (content || []).map((c) => c.text).join('');

// Each entry: what the guide says -> what you must be carrying.
// Spells cost runes; the elemental half is left off deliberately, because a
// staff supplies it and the plugin already models that (ELEMENT_STAVES).
// Law runes are the part no staff covers.
const TRAVEL_COSTS = [
  { re: /\bchronicle\b/i, items: ['chronicle'] },
  { re: /\bhouse tab(let)?\b|\bteleport to house\b/i, items: ['teleport to house'] },
  // "falador teletab" -> the real item is "Falador teleport". A generic
  // "teleport tab" is NOT an item name and would sit red for ever, which
  // is the wave-19 lesson about tablet names that do not exist.
  { re: /\b(\w+)\s*teletabs?\b/i, name: (m) => `${m[1].toLowerCase()} teleport` },
  { re: /\bglory\b/i, items: ['amulet of glory'] },
  { re: /\bring of dueling\b|\bduel(ing)? ring\b/i, items: ['ring of dueling'] },
  { re: /\bgames necklace\b/i, items: ['games necklace'] },
  { re: /\bskills necklace\b/i, items: ['skills necklace'] },
  { re: /\bcombat bracelet\b/i, items: ['combat bracelet'] },
  { re: /\bdigsite (pendant|tele)/i, items: ['digsite pendant'] },
  { re: /\bxeric'?s talisman\b/i, items: ["xeric's talisman"] },
  { re: /\bdrakan'?s medallion\b/i, items: ["drakan's medallion"] },
  { re: /\bslayer ring\b/i, items: ['slayer ring'] },
  { re: /\bring of wealth\b/i, items: ['ring of wealth'] },
  { re: /\bramulet of glory\b/i, items: ['amulet of glory'] },
  // Standard-book teleports: law runes, and the guide names the town.
  { re: /\b(varrock|lumbridge|falador|camelot|ardougne|watchtower)\s*tele(port)?\b/i, items: ['law rune'] },
  { re: /\btele(port)?\s*to\s*(varrock|lumbridge|falador|camelot|ardougne)\b/i, items: ['law rune'] },
];

// "Home tele" is free and needs nothing; a spirit tree, gnome glider,
// charter ship or fairy ring costs no inventory item either. Listed so the
// next person does not "fix" their absence.
const FREE = /\bhome tele|spirit tree|glider|charter|fairy ring|canoe|balloon/i;

const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annotations = JSON.parse(
  fs.readFileSync(path.join(RES, 'annotations/annotations_oziris.json'), 'utf8')).annotations;

const steps = [];
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  const text = runText(step.content);
  steps.push({ text, id: stepId(text), meta: step.metadata || {} });
})));

const findings = [];
steps.forEach((step, index) => {
  for (const cost of TRAVEL_COSTS) {
    const match = step.text.match(cost.re);
    if (!match) continue;
    // A step that BUYS the thing is not a step that needs you to have
    // brought it. "Buy a chronicle and 2 teleport cards from Diango" is
    // where the Chronicle comes from; demanding one there would be red
    // until the moment it stopped mattering.
    // Only PURCHASE verbs, and only when the thing being bought IS the
    // travel item. "get" and "grab" were in this list for one run and
    // silently dropped step 142 ("Use the falador teletab, talk to the
    // squire and GET the portrait"). Then a bare purchase test dropped
    // "Chronicle tele and BUY A NEW KITTEN" (owner: "no chronicle icon or
    // reminder") — the kitten is the purchase, the Chronicle is the
    // journey. So the item must appear AFTER the verb to count as bought:
    //   "Buy a chronicle and 2 teleport cards"  -> bought, skip
    //   "Chronicle tele and buy a new kitten"   -> not bought, keep
    const buy = step.text.search(/\b(buy|buys|buying|bought|purchase)\b/i);
    if (buy >= 0 && match.index > buy) continue;
    const entry = annotations[step.id];
    const have = (entry?.items || []).map((i) => (i.name || '').toLowerCase());
    const wanted = cost.name ? [cost.name(match)] : cost.items;
    const missing = wanted.filter((want) =>
      !have.some((h) => h === want || h.includes(want) || want.includes(h)));
    // Record the step even when its own kit is already correct. What this
    // step NEEDS and what is MISSING from it are different questions, and
    // conflating them made --apply non-idempotent: the first run added the
    // item, which stopped the step being a finding, which meant the second
    // half of the job — the warning on the step BEFORE — could never be
    // written on any subsequent run.
    findings.push({
      index, id: step.id, text: step.text, needs: wanted, missing,
      free: FREE.test(step.text),
      // The step BEFORE is where a warning would actually be readable.
      previous: index > 0
        ? { index: index - 1, id: steps[index - 1].id, text: steps[index - 1].text } : null,
    });
    break; // one finding per step is enough to act on
  }
});

if (process.argv.includes('--json')) {
  console.log(JSON.stringify(findings, null, 1));
  process.exit(0);
}

// --apply writes two things per finding:
//   the ITEM onto the step that needs it, so the panel counts it and the
//     bank-first routing can act on it; and
//   a NOTE onto the step BEFORE, which is the half that actually warns you
//     in time — by the time you read "Chronicle tele" you have left the bank
//     (owner, 2026-08-11, standing on the step before Dragon Slayer).
//
// Runes are held back by default. A law rune requirement is only true if
// you meant to cast; you might be carrying a staff or simply walking, and
// seven steps nagging about runes you deliberately skipped is worse than
// silence. --include-runes opts them in.
if (process.argv.includes('--apply')) {
  const includeRunes = process.argv.includes('--include-runes');
  const FILE = path.join(RES, 'annotations/annotations_oziris.json');
  const doc = JSON.parse(fs.readFileSync(FILE, 'utf8'));
  let items = 0;
  let notes = 0;
  for (const f of findings) {
    const want = f.needs[0];
    if (!includeRunes && /rune$/.test(want)) continue;
    const entry = doc.annotations[f.id] || (doc.annotations[f.id] = {});
    entry.items = entry.items || [];
    if (f.missing.includes(want) && !entry.items.some((i) => (i.name || '').toLowerCase() === want)) {
      entry.items.push({ name: want, quantity: 1 });
      items++;
    }
    if (!f.previous) continue;
    const before = doc.annotations[f.previous.id] || (doc.annotations[f.previous.id] = {});
    // An ITEM ROW, not a sentence. The first version wrote "Bring a
    // chronicle — the next step teleports with it." into the note and the
    // owner asked for the icon and the 0/1 straight away: a line of prose
    // under a list of item rows does not read as one of them.
    //
    // optional AND bringAhead together — see StepAnnotation.ItemNeed. It
    // must not gate the step it is sitting on.
    before.items = before.items || [];
    if (!before.items.some((i) => (i.name || '').toLowerCase() === want)) {
      before.items.push({ name: want, quantity: 1, optional: true, bringAhead: true });
      notes++;
    }
    // Retire the prose version this tool wrote in its first outing.
    if (before.note) {
      const cleaned = before.note.split('\n')
        .filter((l) => !/^Bring a .+ — the next step teleports with it\.$/.test(l.trim()))
        .join('\n').replace(/\n{3,}/g, '\n\n').trim();
      if (cleaned) before.note = cleaned;
      else delete before.note;
    }
  }
  fs.writeFileSync(FILE, JSON.stringify(doc, null, 1) + '\n');
  console.log(`${items} item(s) added, ${notes} advance warning(s) written`
    + `${includeRunes ? '' : ' (runes held back — pass --include-runes)'}.`);
  process.exit(0);
}

// "needs an item" and "does not list it" are different counts, and only the
// second is work. Printing both keeps a re-run from reading as a regression.
const open = findings.filter((f) => f.missing.length);
console.log(`${findings.length} step(s) prescribe travel that costs an item;`
  + ` ${open.length} do not list it\n`);
for (const f of open) {
  console.log(`${String(f.index).padStart(4)}  ${f.text.slice(0, 66)}`);
  console.log(`        needs: ${f.needs.join(', ')}${f.free ? '   (also names a free route)' : ''}`);
  if (f.previous) console.log(`        after: ${f.previous.text.slice(0, 62)}`);
}
