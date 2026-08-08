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
// FIRST NpcStep/ObjectStep's list is the start conversation and nothing
// else. Taking the whole file's dialogue would highlight options from
// halfway through the quest.
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
const RAW = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/'
  + 'src/main/java/com/questhelper/helpers/quests/';

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

const qhPath = (name) => {
  const words = name.split(/[^a-zA-Z0-9]+/).filter(Boolean);
  const cls = words.map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
  return `${words.join('').toLowerCase()}/${cls}.java`;
};

/**
 * The first NpcStep/ObjectStep's own addDialogStep lines. QH declares a
 * step then immediately hangs its dialogue off the same variable, so the
 * variable name is what ties them together.
 */
function startDialog(java) {
  const decl = java.match(/(\w+)\s*=\s*new\s+(?:NpcStep|ObjectStep)\(/);
  if (!decl) return null;
  const variable = decl[1];
  const lines = [];
  const re = new RegExp(`\\b${variable}\\.addDialogStep\\(\\s*"((?:[^"\\\\]|\\\\.)*)"`, 'g');
  let m;
  while ((m = re.exec(java)) !== null) {
    lines.push(m[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\'));
  }
  return lines.length ? { variable, lines } : null;
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
      const java = await cached(RAW + qhPath(meta.quest));
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
