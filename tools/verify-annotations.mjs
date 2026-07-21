#!/usr/bin/env node
// LLM verifier pass over the drafts in tools/draft-annotations.json.
//
// For every skill draft and item draft, Claude reads the FULL step text and
// judges the draft: confirm (correct as extracted), adjust (right idea,
// wrong detail — comes with corrected values), or reject (not a real
// requirement). Each verdict carries a confidence score, flags, and notes.
// review-annotations.mjs then shows these so you review the flagged 20%
// carefully and fast-approve the confirmed rest.
//
// Results are written back INTO the draft file (a `verifier` field per
// draft), so this is safe to interrupt and re-run — already-verified
// drafts are skipped unless --force.
//
// Setup:  cd tools && npm install
// Usage:  node tools/verify-annotations.mjs [--limit N] [--force]
// Auth:   ANTHROPIC_API_KEY env var (or an `ant auth login` profile).

import fs from 'fs';
import crypto from 'crypto';
import path from 'path';
import { fileURLToPath } from 'url';
import Anthropic from '@anthropic-ai/sdk';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GUIDE_FILE = path.join(__dirname, '../src/main/resources/com/bruhsailer/guide/guide_data.json');
const DRAFT_FILE = path.join(__dirname, 'draft-annotations.json');

const MODEL = 'claude-opus-4-8';
const CONCURRENCY = 3;
const FLUSH_EVERY = 10;

const args = process.argv.slice(2);
const force = args.includes('--force');
const limitIndex = args.indexOf('--limit');
const limit = limitIndex >= 0 ? parseInt(args[limitIndex + 1], 10) : Infinity;

// ---- Rebuild stepId -> full step text (same walk as extract-annotations) ----

// MUST match GuideLoader.stepId() in Java exactly.
function stepId(plainText) {
  const normalized = plainText.replace(/\s+/g, ' ').trim().toLowerCase();
  return crypto.createHash('sha256').update(normalized, 'utf8').digest('hex').slice(0, 10);
}

const runText = (runs) => (runs || []).map((r) => r.text).join('');

const guide = JSON.parse(fs.readFileSync(GUIDE_FILE, 'utf8'));
const stepTextById = new Map();
const idCounts = new Map();
guide.chapters.forEach((chapter) => {
  chapter.sections.forEach((section) => {
    section.steps.forEach((step) => {
      const plain = runText(step.content);
      let id = stepId(plain);
      const seen = (idCounts.get(id) || 0) + 1;
      idCounts.set(id, seen);
      if (seen > 1) id = `${id}-${seen}`;

      const fullText = [
        plain,
        ...(step.nestedContent || []).map((n) => runText(n.content)),
        ...(step.additionalContent || []).map(runText),
      ].join('\n');
      const metadata = step.metadata || {};
      stepTextById.set(id, { fullText, itemsNeeded: metadata.items_needed || '' });
    });
  });
});

// ---- The verdict tool (strict: the response always parses) ----

const FLAG_VALUES = [
  'wrong-skill', 'wrong-level', 'not-a-requirement',
  'wrong-item-name', 'wrong-quantity', 'missing-item', 'abstract-item',
  'ambiguous',
];

const VERDICT_TOOL = {
  name: 'emit_verdict',
  description: 'Report whether the drafted annotation matches what the guide step actually requires.',
  strict: true,
  input_schema: {
    type: 'object',
    additionalProperties: false,
    required: ['verdict', 'confidence', 'flags', 'notes', 'corrected_skills', 'corrected_items'],
    properties: {
      verdict: {
        type: 'string',
        enum: ['confirm', 'adjust', 'reject'],
        description: 'confirm = draft is correct as-is; adjust = right idea but a detail is wrong (provide corrections); reject = not a real requirement of this step',
      },
      confidence: { type: 'number', description: 'How sure you are of this verdict, 0.0 to 1.0' },
      flags: { type: 'array', items: { type: 'string', enum: FLAG_VALUES } },
      notes: { type: 'string', description: 'One or two sentences for the human reviewer. Empty string if nothing to say.' },
      corrected_skills: {
        type: 'array',
        description: 'Only for verdict=adjust on a SKILL draft: the full corrected list of {skill, level}. Empty otherwise.',
        items: {
          type: 'object',
          additionalProperties: false,
          required: ['skill', 'level'],
          properties: {
            skill: { type: 'string', description: 'RuneLite Skill enum name, e.g. FIREMAKING' },
            level: { type: 'integer' },
          },
        },
      },
      corrected_items: {
        type: 'array',
        description: 'Only for verdict=adjust on an ITEM draft: the full corrected item list. Empty otherwise.',
        items: {
          type: 'object',
          additionalProperties: false,
          required: ['name', 'quantity'],
          properties: {
            name: { type: 'string', description: 'Exact in-game OSRS item name, lowercased (e.g. "coins", "leather gloves")' },
            quantity: { anyOf: [{ type: 'integer' }, { type: 'null' }] },
          },
        },
      },
    },
  },
};

function skillPrompt(draft, stepText) {
  return [
    'You are verifying a DRAFT auto-extracted annotation for a step of the BRUHsailer OSRS ironman guide.',
    'The annotation claims this step requires reaching certain skill levels. When the player reaches',
    'them in-game, the plugin will auto-tick the step — so a wrong annotation ticks steps early.',
    '',
    `# Step (${draft.where})`,
    stepText.fullText.trim(),
    '',
    '# Drafted skill requirements',
    ...draft.candidates.map((c) => `- ${c.skill} ${c.level} (evidence: "${c.evidence}")`),
    '',
    '# Judge',
    'A requirement is real only if THIS step tells the player to train/reach that level.',
    'Mentions of other steps, commentary ("you could also..."), quest requirements the player',
    'already meets, or numbers that are not levels (world numbers, quantities, coordinates) are NOT requirements.',
    'If several candidates exist, the step\'s true requirement is usually the one its main instruction states.',
    'Call emit_verdict exactly once. Use corrected_skills only with verdict=adjust.',
  ].join('\n');
}

function itemPrompt(draft, stepText) {
  return [
    'You are verifying a DRAFT auto-extracted item list for a step of the BRUHsailer OSRS ironman guide.',
    'The list was parsed from the step\'s free-form "items needed" metadata. The plugin matches these',
    'against the player\'s inventory/bank BY NAME, so names must be the exact in-game OSRS item names',
    '(lowercased) and quantities must match what the step actually needs.',
    '',
    `# Step (${draft.where})`,
    stepText.fullText.trim(),
    '',
    `# Raw "items needed" metadata`,
    JSON.stringify(draft.raw),
    '',
    '# Parsed draft',
    ...draft.items.map((it) => `- ${it.quantity ?? 1}x ${it.name}`),
    '',
    '# Judge',
    'confirm if every parsed name is a real OSRS item name and quantities are right.',
    'adjust (with corrected_items) if a name is off ("gp" -> "coins", wrong plurality vs the in-game name),',
    'a quantity is wrong, or the raw metadata lists an item the parse missed (flag missing-item).',
    'reject if the metadata is abstractions the plugin cannot count ("melee gear", "tbd", "food") — flag abstract-item.',
    'Call emit_verdict exactly once. Use corrected_items only with verdict=adjust.',
  ].join('\n');
}

// ---- Run ----

const client = new Anthropic();
const draft = JSON.parse(fs.readFileSync(DRAFT_FILE, 'utf8'));

const jobs = [];
for (const d of draft.drafts) {
  if (!d.rejected && (force || !d.verifier)) jobs.push({ kind: 'skill', d });
}
for (const d of draft.itemDrafts || []) {
  if (!d.rejected && (force || !d.verifier)) jobs.push({ kind: 'item', d });
}
jobs.splice(limit);

if (jobs.length === 0) {
  console.log('Nothing to verify — every draft already has a verdict (use --force to redo).');
  process.exit(0);
}
console.log(`Verifying ${jobs.length} draft(s) with ${MODEL}...`);

const flush = () => fs.writeFileSync(DRAFT_FILE, JSON.stringify(draft, null, 2) + '\n');

let done = 0;
let failed = 0;
const tally = { confirm: 0, adjust: 0, reject: 0 };

async function verifyOne({ kind, d }) {
  const stepText = stepTextById.get(d.stepId);
  if (!stepText) {
    // The guide moved on since the drafts were extracted; nothing to verify against.
    d.verifier = { verdict: 'reject', confidence: 1, flags: ['ambiguous'], notes: 'Step no longer exists in the current guide data — re-run extract-annotations.', model: 'n/a', verifiedOn: today() };
    tally.reject++;
    return;
  }
  const prompt = kind === 'skill' ? skillPrompt(d, stepText) : itemPrompt(d, stepText);
  const response = await client.messages.create({
    model: MODEL,
    max_tokens: 2048,
    tools: [VERDICT_TOOL],
    tool_choice: { type: 'tool', name: 'emit_verdict' },
    messages: [{ role: 'user', content: prompt }],
  });
  const call = response.content.find((block) => block.type === 'tool_use');
  if (!call) throw new Error('model did not call emit_verdict');
  const v = call.input;
  d.verifier = {
    verdict: v.verdict,
    confidence: v.confidence,
    flags: v.flags,
    notes: v.notes,
    ...(v.corrected_skills.length > 0 ? { correctedSkills: v.corrected_skills } : {}),
    ...(v.corrected_items.length > 0 ? { correctedItems: v.corrected_items } : {}),
    model: MODEL,
    verifiedOn: today(),
  };
  tally[v.verdict] = (tally[v.verdict] || 0) + 1;
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

const queue = [...jobs];
async function worker() {
  for (;;) {
    const job = queue.shift();
    if (!job) return;
    try {
      await verifyOne(job);
    } catch (error) {
      failed++;
      console.error(`  ! ${job.d.stepId} (${job.kind}): ${error.message}`);
      if (error.status === 401) {
        console.error('Authentication failed — set ANTHROPIC_API_KEY (or `ant auth login`) and re-run.');
        process.exit(1);
      }
    }
    done++;
    if (done % FLUSH_EVERY === 0) {
      flush();
      console.log(`  ${done}/${jobs.length} (confirm ${tally.confirm}, adjust ${tally.adjust}, reject ${tally.reject})`);
    }
  }
}

await Promise.all(Array.from({ length: CONCURRENCY }, worker));
flush();

console.log(`\nDone: ${tally.confirm} confirmed, ${tally.adjust} adjusted, ${tally.reject} rejected, ${failed} failed.`);
console.log('Run `node tools/review-annotations.mjs` — confirmed drafts can be fast-approved, flagged ones deserve a look.');
