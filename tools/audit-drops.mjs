#!/usr/bin/env node
// Drop-table cross-check: every combat-acquisition step ("safespot the
// zamorak warrior until you get a rune scimitar") names an NPC and an
// item — the wiki knows whether that NPC actually DROPS that item.
// A mismatch means the step will point players (and our overlays) at
// the wrong monster. Run after GoalAuditDumpTest like audit-goals:
//
//   gradlew test --tests "*.GoalAuditDumpTest"
//   node tools/audit-drops.mjs
//
// Verdicts: OK (item on the NPC's drop table), MISSING (page found,
// item not in its drops — investigate!), NO PAGE (couldn't resolve the
// NPC name — usually the extraction guessed a non-NPC phrase; verify by
// hand and ignore if the step is fine).

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const CACHE_DIR = path.join(ROOT, 'tools', '.wiki-cache');

const tsv = path.join(ROOT, 'build/goal-audit.tsv');
if (!fs.existsSync(tsv)) {
  console.error('build/goal-audit.tsv missing — run: gradlew test --tests "*.GoalAuditDumpTest"');
  process.exit(1);
}

// "safespot the zamorak warrior until ..." -> "zamorak warrior".
// The NPC phrase runs from the combat verb to the first stop word.
const COMBAT = /\b(?:kill|slay|safespot|fight)\s+(?:a\s+|an\s+|the\s+|\d+\s+)*([a-z][a-z' -]*?)(?=\s+(?:until|for|at|in|near|and|with|to|from|x\d)\b|\s*[,.(]|$)/i;

// Goal items that aren't literal drops — skip, they'd always flag.
const NOT_DROPS = new Set(['coins', 'gp', 'gold', 'cash', 'money']);

async function fetchCached(url) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const cacheFile = path.join(CACHE_DIR, url.replace(/[^a-z0-9.]+/gi, '_'));
  if (fs.existsSync(cacheFile)) {
    return fs.readFileSync(cacheFile, 'utf8');
  }
  const res = await fetch(url, { headers: { 'user-agent': 'ironscape-optimal audit tool' } });
  if (!res.ok) {
    return null; // cache misses aren't cached: a rename may fix them
  }
  const text = await res.text();
  fs.writeFileSync(cacheFile, text);
  return text;
}

// The NPC's wiki source, trying the phrase then its singular.
async function npcPage(phrase) {
  const candidates = [phrase];
  if (phrase.endsWith('s')) {
    candidates.push(phrase.slice(0, -1));
  }
  for (const candidate of candidates) {
    const page = candidate[0].toUpperCase() + candidate.slice(1);
    const raw = await fetchCached('https://oldschool.runescape.wiki/w/'
      + encodeURIComponent(page.replace(/ /g, '_')) + '?action=raw');
    if (raw != null) {
      // Follow a #REDIRECT ("Zamorak warriors" -> "Zamorak warrior").
      const redirect = raw.match(/^#REDIRECT\s*\[\[([^\]]+)\]\]/i);
      if (redirect) {
        return npcPage(redirect[1]);
      }
      return { name: page, raw };
    }
  }
  return null;
}

// Species fallback, mirroring the plugin's combat-sub matching: "a rat"
// means any NPC whose name ENDS with "rat" ("Giant rat"), "a bear" any
// "* bear". Wiki title search finds the species pages to check.
async function speciesPages(phrase) {
  const word = phrase.split(' ').pop();
  const api = 'https://oldschool.runescape.wiki/api.php?action=query&list=search'
    + '&srsearch=' + encodeURIComponent('intitle:' + word)
    + '&srlimit=20&format=json';
  const body = await fetchCached(api);
  if (body == null) return [];
  const titles = (JSON.parse(body).query?.search ?? []).map((s) => s.title);
  return titles.filter((t) => new RegExp('(^|\\s)' + word + 's?$', 'i').test(t)).slice(0, 6);
}

function dropsItem(raw, item) {
  // {{DropsLine|name=Rune scimitar|...}} — tolerate case and spacing.
  const pattern = new RegExp(
    'DropsLine[^}]*\\|\\s*name\\s*=\\s*' + item.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
    'i');
  return pattern.test(raw);
}

// One check per (sub, npc, item) triple.
const seen = new Set();
const checks = [];
for (const line of fs.readFileSync(tsv, 'utf8').split('\n')) {
  if (!line.startsWith('ITEM\t')) continue;
  const [, subId, , itemName, , text] = line.split('\t');
  const combat = text.match(COMBAT);
  if (!combat || NOT_DROPS.has(itemName.toLowerCase().trim())) continue;
  const npc = combat[1].trim();
  const key = subId + '|' + npc + '|' + itemName;
  if (seen.has(key)) continue;
  seen.add(key);
  checks.push({ subId, npc, item: itemName.trim(), text });
}

console.log(`=== DROP-TABLE cross-check: ${checks.length} combat-acquisition goal(s) ===\n`);
let missing = 0;
let noPage = 0;
for (const check of checks) {
  const page = await npcPage(check.npc.toLowerCase());
  if (page == null) {
    noPage++;
    console.log(`? NO PAGE  [${check.subId}] "${check.npc}" — ${check.text.slice(0, 80)}`);
    continue;
  }
  if (dropsItem(page.raw, check.item)) {
    console.log(`  OK       [${check.subId}] ${page.name} drops "${check.item}"`);
    continue;
  }
  // The named page doesn't drop it — a SPECIES page might ("rat" is
  // really "Giant rat"). Same rule the plugin's outlines use.
  let via = null;
  for (const title of await speciesPages(check.npc.toLowerCase())) {
    const raw = await fetchCached('https://oldschool.runescape.wiki/w/'
      + encodeURIComponent(title.replace(/ /g, '_')) + '?action=raw');
    if (raw != null && dropsItem(raw, check.item)) {
      via = title;
      break;
    }
  }
  if (via != null) {
    console.log(`  OK       [${check.subId}] "${check.npc}" -> ${via} drops "${check.item}"`);
  } else {
    missing++;
    console.log(`! MISSING  [${check.subId}] ${page.name} does NOT drop "${check.item}" — ${check.text.slice(0, 80)}`);
  }
}
console.log(`\n${missing} mismatch(es), ${noPage} unresolved NPC name(s) of ${checks.length} checks`);
