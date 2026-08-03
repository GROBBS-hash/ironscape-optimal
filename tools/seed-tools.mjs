#!/usr/bin/env node
// Seeds REQUIRED TOOLS for gather steps: "mine 2 blurite ore" needs a
// pickaxe, "chop 110 logs" an axe, "fish shrimp" a small fishing net.
// The tool lands in the step's annotation items -> a live have/need
// badge (bare "pickaxe"/"axe" already count any metal tier via
// ItemTracker.SUBSTITUTES).
//
// Existing annotation items are MERGED; a step that already lists the
// tool (any spelling that contains it) is skipped. --dry-run prints.
//
// Usage: node tools/seed-tools.mjs [--dry-run]

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const GUIDE_FILE = path.join(RES, 'guide/guide_data_oziris.json');
const ANNOTATIONS_FILE = path.join(RES, 'annotations/annotations_oziris.json');
import crypto from 'crypto';

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const runText = (rs) => (rs || []).map((r) => r.text).join('');

// Ordered rules: first match wins per rule, a step can hit several rules.
const RULES = [
  // mine as a VERB ("mine 2 blurite ore"), not the noun ("in the mines")
  { re: /\bmine\s+(?:\d[\d,]*k?\s+)?[a-z]/i, tools: ['pickaxe'] },
  { re: /\bchop\b|\bcut\s+(?:down\s+)?(?:a\s+|the\s+)?(?:\w+\s+)?(?:trees?|logs?)\b/i, tools: ['axe'] },
  // "fish shrimp", never "buy X from the fish shop"
  { re: /\bfish\b(?!(?:ing)?\s+shop).*\b(?:shrimps?|anchov|monkfish)|\b(?:shrimps?|anchovies|monkfish)\b.*\bfish\b(?!(?:ing)?\s+shop)/i, tools: ['small fishing net'] },
  { re: /\bfish\b(?!(?:ing)?\s+shop).*\b(?:sardines?|herrings?)|\b(?:sardines?|herrings?)\b.*\bfish\b(?!(?:ing)?\s+shop)/i, tools: ['fishing rod', 'fishing bait'] },
  { re: /\bfish\b(?!(?:ing)?\s+shop).*\b(?:trout|salmon)|\b(?:trout|salmon)\b.*\bfish\b(?!(?:ing)?\s+shop)/i, tools: ['fly fishing rod', 'feather'] },
  { re: /\bfish\b(?!(?:ing)?\s+shop).*\blobsters?\b/i, tools: ['lobster pot'] },
  { re: /\bfish\b(?!(?:ing)?\s+shop).*\b(?:tuna|swordfish)/i, tools: ['harpoon'] },
  { re: /\b(?:light|burn)\b.*\b(?:logs?|fire)\b/i, tools: ['tinderbox'] },
  { re: /\bsmith\b/i, tools: ['hammer'] },
  { re: /\bcatch\b.*\bbutterfl/i, tools: ['butterfly net', 'butterfly jar'] },
  // hunting chins, not throwing them at something ("crabs with chins")
  { re: /\b(?:catch|hunt)\b[^.,]{0,40}\bchin(?:chompa)?s?\b/i, tools: ['box trap'] },
];

const guide = JSON.parse(fs.readFileSync(GUIDE_FILE, 'utf8'));
const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));

let applied = 0;
for (const ch of guide.chapters) {
  for (const sec of ch.sections) {
    for (const step of sec.steps) {
      const text = [runText(step.content),
        ...(step.nestedContent || []).map((n) => runText(n.content))].join(' ');
      const wanted = [];
      for (const rule of RULES) {
        if (rule.re.test(text)) wanted.push(...rule.tools);
      }
      if (!wanted.length) continue;
      const id = stepId(runText(step.content));
      const existing = annotations.annotations[id]?.items || [];
      const lowerText = text.toLowerCase();
      const missing = [...new Set(wanted)].filter((tool) =>
        // already annotated (any tier: "rune pickaxe" contains "pickaxe")
        !existing.some((e) => (e.name || '').toLowerCase().includes(tool))
        // the step TEXT already tells you to bring/buy it -> the item
        // detector covers it ("Take steel axe and a tinderbox")
        && !lowerText.includes(tool));
      if (!missing.length) continue;
      console.log(`HIT  ${id} + ${missing.join(', ')} | ${runText(step.content).slice(0, 80)}`);
      if (!process.argv.includes('--dry-run')) {
        annotations.annotations[id] = {
          ...(annotations.annotations[id] || {}),
          items: [...existing, ...missing.map((name) => ({ name, quantity: 1 }))],
        };
        applied++;
      }
    }
  }
}
if (applied > 0) {
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
}
console.log(`\napplied tools to ${applied} step(s)`);
