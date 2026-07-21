#!/usr/bin/env node
// Scans the guide text for skill-level statements ("until 70 range",
// "get 43 prayer") and writes DRAFT annotation candidates to
// tools/draft-annotations.json. Nothing is applied automatically —
// run review-annotations.mjs afterwards to approve/reject each one.
//
// Usage: node tools/extract-annotations.mjs

import fs from 'fs';
import crypto from 'crypto';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GUIDE_FILE = path.join(__dirname, '../src/main/resources/com/bruhsailer/guide/guide_data.json');
const DRAFT_FILE = path.join(__dirname, 'draft-annotations.json');

// Canonical RuneLite Skill enum name -> the words the guide might use.
// Aliases must be whole words ("att" will not match inside "attack").
const SKILLS = {
  ATTACK: ['attack', 'att'],
  STRENGTH: ['strength', 'str'],
  DEFENCE: ['defence', 'defense', 'def'],
  HITPOINTS: ['hitpoints', 'hitpoint', 'hp'],
  RANGED: ['ranged', 'range', 'ranging'],
  PRAYER: ['prayer', 'pray'],
  MAGIC: ['magic', 'mage'],
  COOKING: ['cooking', 'cook'],
  WOODCUTTING: ['woodcutting', 'wc'],
  FLETCHING: ['fletching', 'fletch'],
  FISHING: ['fishing', 'fish'],
  FIREMAKING: ['firemaking', 'fm'],
  CRAFTING: ['crafting', 'craft'],
  SMITHING: ['smithing', 'smith'],
  MINING: ['mining'],
  HERBLORE: ['herblore', 'herb'],
  AGILITY: ['agility', 'agi'],
  THIEVING: ['thieving', 'thieve', 'thiev'],
  SLAYER: ['slayer', 'slay'],
  FARMING: ['farming', 'farm'],
  RUNECRAFT: ['runecraft', 'runecrafting', 'rc'],
  HUNTER: ['hunter', 'hunting'],
  CONSTRUCTION: ['construction', 'con'],
};

const aliasToSkill = {};
for (const [skill, aliases] of Object.entries(SKILLS)) {
  for (const alias of aliases) aliasToSkill[alias] = skill;
}
const skillWords = Object.keys(aliasToSkill).join('|');

// Verb-anchored phrasings are almost always real requirements (high
// confidence). A bare "43 prayer" might just be commentary (low).
const PATTERNS = [
  { re: new RegExp(String.raw`\b(?:get|reach|until|to|hit|at)\s+(?:level\s+)?(\d{1,2})\s+(${skillWords})\b`, 'gi'), level: 1, skill: 2, confidence: 'high' },
  { re: new RegExp(String.raw`\b(${skillWords})\s+(?:to|until)\s+(?:level\s+)?(\d{1,2})\b`, 'gi'), level: 2, skill: 1, confidence: 'high' },
  { re: new RegExp(String.raw`\b(\d{1,2})\s+(${skillWords})\b`, 'gi'), level: 1, skill: 2, confidence: 'low' },
];

// MUST match GuideLoader.stepId() in Java exactly: id = first 10 hex chars
// of SHA-256 over the whitespace-collapsed, lowercased step text.
function stepId(plainText) {
  const normalized = plainText.replace(/\s+/g, ' ').trim().toLowerCase();
  return crypto.createHash('sha256').update(normalized, 'utf8').digest('hex').slice(0, 10);
}

const runText = (runs) => (runs || []).map((r) => r.text).join('');

const guide = JSON.parse(fs.readFileSync(GUIDE_FILE, 'utf8'));

const drafts = [];
const itemDrafts = [];
const idCounts = new Map();
let totalSteps = 0;

guide.chapters.forEach((chapter, ci) => {
  chapter.sections.forEach((section, si) => {
    section.steps.forEach((step, ti) => {
      totalSteps++;
      // The id hashes ONLY the main content (same as the Java loader) …
      const plain = runText(step.content);
      let id = stepId(plain);
      const seen = (idCounts.get(id) || 0) + 1;
      idCounts.set(id, seen);
      if (seen > 1) id = `${id}-${seen}`;

      // … but we SEARCH the nested bullets and extra paragraphs too.
      const searchable = [
        plain,
        ...(step.nestedContent || []).map((n) => runText(n.content)),
        ...(step.additionalContent || []).map(runText),
      ].join('\n');

      const candidates = new Map(); // "SKILL:level" -> candidate
      for (const { re, level, skill, confidence } of PATTERNS) {
        for (const m of searchable.matchAll(re)) {
          const lvl = parseInt(m[level], 10);
          const sk = aliasToSkill[m[skill].toLowerCase()];
          if (!sk || lvl < 2 || lvl > 99) continue;
          const key = `${sk}:${lvl}`;
          // keep the highest-confidence sighting of each skill:level
          if (!candidates.has(key) || (candidates.get(key).confidence === 'low' && confidence === 'high')) {
            candidates.set(key, { skill: sk, level: lvl, confidence, evidence: m[0].trim() });
          }
        }
      }

      if (candidates.size > 0) {
        drafts.push({
          stepId: id,
          where: `${section.title} — step ${ti + 1}`,
          excerpt: plain.replace(/\s+/g, ' ').trim().slice(0, 220),
          candidates: [...candidates.values()].sort((a, b) =>
            a.confidence === b.confidence ? b.level - a.level : a.confidence === 'high' ? -1 : 1),
        });
      }

      const items = parseItemsNeeded((step.metadata || {}).items_needed);
      if (items.length > 0) {
        itemDrafts.push({
          stepId: id,
          where: `${section.title} — step ${ti + 1}`,
          raw: step.metadata.items_needed,
          items,
        });
      }
    });
  });
});

// "232 gp, knife, 2 buckets" -> [{name:'coins',quantity:232},{name:'knife'},{name:'bucket',quantity:2}]
// Prose fragments ("will need to bank while...") are dropped; the review
// step catches anything the parser got wrong.
function parseItemsNeeded(raw) {
  if (!raw || /^(none|nothing|n\/a|-*)$/i.test(raw.trim())) return [];
  const items = [];
  for (let token of raw.split(/,/)) {
    token = token.trim();
    if (!token || token.length > 40) continue;
    if (/\b(will|while|need to|during|if you)\b/i.test(token)) continue; // prose, not an item

    const gp = token.match(/^([\d,]+)\s*gp$/i);
    if (gp) {
      items.push({ name: 'coins', quantity: parseInt(gp[1].replace(/,/g, ''), 10) });
      continue;
    }
    const m = token.match(/^([\d,]+)\s+(.+)$/);
    if (m) {
      items.push({ name: m[2].trim().toLowerCase(), quantity: parseInt(m[1].replace(/,/g, ''), 10) });
    } else {
      items.push({ name: token.toLowerCase() });
    }
  }
  return items;
}

// Re-running after a guide refresh must not lose review work: carry the
// `rejected` flag and the verifier's verdict over from the previous draft
// file for any step whose id (= text hash) is unchanged. Same id means the
// same text, and extraction is deterministic, so the old decision still
// applies. New/edited steps arrive without either field — exactly the
// ones that still need verification and review.
if (fs.existsSync(DRAFT_FILE)) {
  const previous = JSON.parse(fs.readFileSync(DRAFT_FILE, 'utf8'));
  const carryOver = (fresh, old) => {
    const byId = new Map((old || []).map((d) => [d.stepId, d]));
    let kept = 0;
    for (const d of fresh) {
      const prior = byId.get(d.stepId);
      if (!prior) continue;
      if (prior.rejected) d.rejected = true;
      if (prior.verifier) d.verifier = prior.verifier;
      if (prior.rejected || prior.verifier) kept++;
    }
    return kept;
  };
  const keptSkills = carryOver(drafts, previous.drafts);
  const keptItems = carryOver(itemDrafts, previous.itemDrafts);
  if (keptSkills + keptItems > 0) {
    console.log(`Carried over ${keptSkills + keptItems} earlier verdict/rejection decision(s) for unchanged steps.`);
  }
}

fs.writeFileSync(DRAFT_FILE, JSON.stringify(
  { generatedOn: new Date().toISOString().slice(0, 10), drafts, itemDrafts }, null, 2) + '\n');

const high = drafts.filter((d) => d.candidates.some((c) => c.confidence === 'high'));
console.log(`Scanned ${totalSteps} steps.`);
console.log(`${drafts.length} steps have skill-level candidates (${high.length} with at least one high-confidence match).`);
console.log(`${itemDrafts.length} steps have item-need candidates.`);
console.log(`Draft written to ${path.relative(process.cwd(), DRAFT_FILE)}`);
console.log('Next: node tools/review-annotations.mjs');
