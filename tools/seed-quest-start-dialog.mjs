// Seeds `dialog` on quest START steps from Quest Helper's own strings.
//
// Why start steps specifically: the plugin's free, unseeded dialogue
// highlighting only runs while a quest is IN_PROGRESS (so an unrelated
// NPC offering "Your quest." stays untouched). On a start step the quest
// is NOT_STARTED by definition, so the one conversation where you most
// need to be told which option begins the quest is the one that gets no
// help at all. And the generic set — "Your quest", the quest name — could
// never match an opener like "I was wondering what was down those
// stairs?" anyway (owner, 2026-08-08, at Vestri).
//
// QH attaches its addDialogStep calls to the step they belong to, so the
// first NpcStep's list is the start conversation and nothing else.
// Taking the whole file's dialogue would highlight options from halfway
// through the quest — see startDialog for the two rules and the quests
// that taught them.
//
// A step with no entry here is not a gap to fill: 14 of 33 start steps
// resolve to a QH helper whose opening conversation simply has no
// options (you talk to Hassan and Prince Ali Rescue begins). Only one
// quest in the guide has no helper namespace at all, and miniquests are
// now searched too, so that is covered.
//
//   node tools/seed-quest-start-dialog.mjs            # draft, changes nothing
//   node tools/seed-quest-start-dialog.mjs --apply
//
// Existing dialog entries are never overwritten — hand-authored ones win.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const CACHE = path.join(__dirname, '.qh-cache');

const apply = process.argv.includes('--apply');
const annFile = path.join(RES, 'annotations/annotations_oziris.json');
const guide = JSON.parse(fs.readFileSync(
  path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annDoc = JSON.parse(fs.readFileSync(annFile, 'utf8'));

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);

async function cached(url) {
  fs.mkdirSync(CACHE, { recursive: true });
  const file = path.join(CACHE, url.replace(/[^a-z0-9.]+/gi, '_'));
  if (fs.existsSync(file)) return fs.readFileSync(file, 'utf8');
  const res = await fetch(url);
  const text = res.ok ? await res.text() : '';
  fs.writeFileSync(file, text);
  return text;
}

// Resolve the helper by LISTING the repo rather than guessing filenames.
// Guessing failed on 12 of 33 quests and every failure was a spelling the
// game and the guide disagree about: Vampyre vs Vampire, "Romeo & Juliet"
// vs romeoandjuliet, the apostrophe in "The Knight's Sword". Exactly the
// class that also broke quest-name lookup in the Quest enum.
const API = 'https://api.github.com/repos/Zoinkwiz/quest-helper/contents/'
  + 'src/main/java/com/questhelper/helpers/quests';
const slug = (s) => s.toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]/g, '');

// Miniquests are a separate namespace in QH. Alfred Grimhand's Barcrawl
// is the only one the guide starts, and it was the single quest with no
// directory anywhere under quests/.
const MINI = 'https://api.github.com/repos/Zoinkwiz/quest-helper/contents/'
  + 'src/main/java/com/questhelper/helpers/miniquests';
const dirBySlug = new Map();
for (const [base, listing] of [[API, JSON.parse(await cached(API))],
  [MINI, JSON.parse(await cached(MINI))]]) {
  for (const d of listing) dirBySlug.set(slug(d.name), { dir: d.name, base });
}
// The guide's tag spells some quests differently from QH's folder; these
// are the survivors after slug-normalising, each checked by hand.
const DIR_ALIASES = {
  'vampireslayer': 'vampyreslayer',
  'ragandboneman': 'ragandboneman',
  'recipefordisasterevildave': 'recipefordisaster',
  'dragonslayeri': 'dragonslayer',
  'fairytaleigrowingpains': 'fairytalei',
  'fairytaleiicureaqueen': 'fairytaleii',
};

async function qhSource(questName) {
  const s = slug(questName);
  const found = dirBySlug.get(s) ?? dirBySlug.get(DIR_ALIASES[s] ?? '');
  if (!found) return '';
  const listing = JSON.parse(await cached(`${found.base}/${found.dir}`));
  const javas = listing.filter((f) => f.name.endsWith('.java'));
  if (!javas.length) return '';

  // Recipe for Disaster keeps ONE FILE PER SUBQUEST (RFDEvilDave.java,
  // RFDPiratePete.java...), so taking whichever .java sorts first landed
  // on AskAboutFishCake. When the guide's tag carries the subquest in
  // parentheses, match on that; otherwise prefer the file named after the
  // directory itself.
  const parenthetical = questName.match(/\(([^)]+)\)/);
  if (parenthetical) {
    const want = slug(parenthetical[1]);
    const hit = javas.find((f) => slug(f.name.replace(/\.java$/, '')).includes(want));
    if (hit) return cached(hit.download_url);
  }
  const named = javas.find((f) => slug(f.name.replace(/\.java$/, '')) === slug(found.dir));
  return cached((named ?? javas[0]).download_url);
}

const dialogOf = (java, variable) => {
  const lines = [];
  const re = new RegExp(`\\b${variable}\\.addDialogStep\\(\\s*"((?:[^"\\\\]|\\\\.)*)"`, 'g');
  let m;
  while ((m = re.exec(java)) !== null) {
    lines.push(m[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\'));
  }
  return lines;
};

/**
 * The opening CONVERSATION's dialogue: the first NpcStep's own
 * addDialogStep lines. QH hangs dialogue off the step variable, so the
 * variable name ties them together.
 *
 * Two rules, both learned from the quests the first cut got wrong:
 *
 * Ignore leading ObjectSteps. Rune Mysteries opens with goUpToHoracio —
 * a staircase, no dialogue — and the actual opener is the talkToHoracio
 * NpcStep right after it. Taking the first step of ANY kind found the
 * stairs and gave up.
 *
 * If that first NpcStep has no dialogue, seed NOTHING. Prince Ali Rescue
 * starts by talking to Hassan with no options at all; the first step that
 * does have dialogue is talkToNed, halfway through, making a wig. "No
 * options here" is the right answer, not a reason to keep searching.
 */
function startDialog(java) {
  const npc = java.match(/(\w+)\s*=\s*new\s+NpcStep\(/);
  if (npc) {
    const lines = dialogOf(java, npc[1]);
    return lines.length ? { variable: npc[1], lines } : null;
  }
  // No NpcStep anywhere: a quest begun by using an object.
  const obj = java.match(/(\w+)\s*=\s*new\s+ObjectStep\(/);
  if (!obj) return null;
  const lines = dialogOf(java, obj[1]);
  return lines.length ? { variable: obj[1], lines } : null;
}

const rows = [];
for (const chapter of guide.chapters) {
  for (const section of chapter.sections) {
    for (const step of section.steps) {
      const meta = step.metadata || {};
      if (!meta.quest || meta.questStatus !== 'start') continue;
      const text = (step.content || []).map((r) => r.text).join('');
      const id = stepId(text);
      const existing = annDoc.annotations[id];
      if (existing && Array.isArray(existing.dialog) && existing.dialog.length) {
        rows.push({ id, quest: meta.quest, text, skip: 'already has dialog' });
        continue;
      }
      const java = await qhSource(meta.quest);
      if (!java) { rows.push({ id, quest: meta.quest, text, skip: 'no QH helper' }); continue; }
      const found = startDialog(java);
      if (!found) { rows.push({ id, quest: meta.quest, text, skip: 'no dialogue on first step' }); continue; }
      rows.push({ id, quest: meta.quest, text, dialog: found.lines, variable: found.variable });
    }
  }
}

const seeded = rows.filter((r) => r.dialog);
for (const r of seeded) {
  console.log(`\n[${r.id}] ${r.quest}  (QH step: ${r.variable})`);
  console.log(`   ${r.text.slice(0, 78)}`);
  for (const line of r.dialog) console.log(`     - ${line}`);
}
console.log(`\n${seeded.length} start steps would be seeded`);
for (const reason of ['already has dialog', 'no QH helper', 'no dialogue on first step']) {
  const n = rows.filter((r) => r.skip === reason).length;
  if (n) console.log(`${n} skipped: ${reason}`);
}

if (!apply) {
  console.log('\n(draft only - re-run with --apply to write)');
} else {
  for (const r of seeded) {
    annDoc.annotations[r.id] = annDoc.annotations[r.id] || {};
    annDoc.annotations[r.id].dialog = r.dialog;
  }
  // 1-space indent: matches the file the seeders already share.
  fs.writeFileSync(annFile, JSON.stringify(annDoc, null, 1) + '\n');
  console.log(`\napplied to ${path.relative(process.cwd(), annFile)}`);
}
