#!/usr/bin/env node
/**
 * audit-note-items.mjs — items a step's NOTE tells you to bring, that the
 * step does not list as an item row.
 *
 * WHY. "Do Jungle potion" carries the note "Bring a small fish net with u
 * and fish ~100 karambwanji" and, separately, "Recommended: Food,
 * Antipoison." All three were prose. The owner's point when we fixed that
 * one by hand: prose under a list of item rows does not read as one of
 * them, so you walk off without the net. The same complaint produced
 * ItemNeed.bringAhead in wave 24 for exactly this reason.
 *
 * This finds the rest of that class rather than meeting them one step at
 * a time.
 *
 * DELIBERATELY CONSERVATIVE. It only reports an item when the note uses a
 * CARRY phrasing ("bring", "take", "with you", "recommended:"), because a
 * note that merely mentions an item is not telling you to pack one —
 * "you can feed caviar and roe to a kitten" names two items and asks for
 * neither. Everything else is listed separately as MENTIONS for a human
 * to skim, never seeded.
 *
 * Usage:
 *   node tools/audit-note-items.mjs           # report
 *   node tools/audit-note-items.mjs --all     # + the mentions it ignored
 */

import fs from 'fs';
import path from 'path';
import os from 'os';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const RES = path.join(repo, 'src/main/resources/com/ironscape');
const showAll = process.argv.includes('--all');

// ---- known item names, same authorities audit-goals uses ---------------
const known = new Set();
{
	const mapping = path.join(here, 'wiki-item-mapping.json');
	if (fs.existsSync(mapping)) {
		for (const item of JSON.parse(fs.readFileSync(mapping, 'utf8'))) {
			known.add(item.name.toLowerCase());
		}
	}
	const cache = path.join(here, '.wiki-cache/item-names-cache.json');
	if (fs.existsSync(cache)) {
		const raw = JSON.parse(fs.readFileSync(cache, 'utf8'));
		const values = Array.isArray(raw) ? raw : Object.values(raw);
		for (const v of values) {
			if (typeof v === 'string') known.add(v.toLowerCase());
		}
	}
	for (const name of Object.keys(JSON.parse(
		fs.readFileSync(path.join(RES, 'items/item_ids.json'), 'utf8')))) {
		known.add(name.toLowerCase());
	}
}
if (known.size < 1000) {
	console.error('Item name corpus looks too small — run audit-goals once to warm its caches.');
	process.exit(1);
}

// Words that are real item names but almost never mean "pack one" in prose.
const NOISE = new Set(['coins', 'gold', 'cash', 'rope', 'bones', 'coal', 'logs',
	'plank', 'planks', 'seed', 'seeds', 'herb', 'herbs', 'bar', 'bars', 'ore',
	'ashes', 'feather', 'feathers', 'bucket', 'pot', 'jug', 'vial', 'cake',
	'bread', 'meat', 'fish', 'wine', 'beer', 'ale', 'stew', 'pie', 'egg']);

const CARRY = /\b(bring|take|carry|pack|with (?:u|you)|recommended:|make sure you have|have with)\b/i;

/**
 * Named in a carry sentence but NOT a carry item, with the reason.
 * Keyed by step index and canonical name. Without this the two below come
 * back on every run, and a check that re-asks a settled question is one
 * nobody reads — the lesson recorded in decisions-declined.json.
 */
const NOT_CARRIED = {
	'301:caviar': 'You MAKE caviar by cutting the fish you catch; the knife is the carry item, and it is seeded.',
	'509:bucket': '"25k buckets worth of sand" is a measure of sand for the grinder, not buckets to carry.',
};

const guide = JSON.parse(fs.readFileSync(
	path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annRoot = JSON.parse(fs.readFileSync(
	path.join(RES, 'annotations/annotations_oziris.json'), 'utf8'));
const ann = annRoot.annotations || annRoot;

const manifestPath = path.join(os.homedir(), '.runelite/ironscape/guide_manifest.json');
if (!fs.existsSync(manifestPath)) {
	console.error('No guide manifest — run the client once so step ids exist.');
	process.exit(1);
}
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const ids = manifest.variants.OZIRIS.steps.map((s) => s.id);

const steps = [];
for (const ch of guide.chapters) {
	for (const sec of ch.sections) {
		for (const st of sec.steps) steps.push(st);
	}
}
const textOf = (st) => (st.content || []).map((c) => c.text).join('').trim();
const notesOf = (st) => (st.additionalContent || [])
	.map((runs) => runs.map((r) => r.text).join('')).join('\n');

/** Longest known item name appearing in the text, word-bounded. */
function itemsIn(text) {
	const lower = ' ' + text.toLowerCase().replace(/[^a-z0-9' ]/g, ' ') + ' ';
	const found = new Set();
	for (const name of known) {
		if (name.length < 4 || NOISE.has(name)) continue;
		if (lower.includes(' ' + name + ' ')) found.add(name);
	}
	// Drop any name wholly inside a longer one we also matched.
	for (const a of [...found]) {
		for (const b of found) {
			if (a !== b && b.includes(a)) {
				found.delete(a);
				break;
			}
		}
	}
	return [...found];
}

const seedable = [];
const mentions = [];
steps.forEach((step, i) => {
	const note = notesOf(step);
	if (!note) return;
	const items = itemsIn(note);
	if (!items.length) return;
	const entry = ann[ids[i]] || {};
	// Compare CANONICALLY or the tool never notices it has been satisfied:
	// seeding "waterskin" left the note's "waterskins" still reported, and
	// "dwarven stout(m)" left "dwarven stout" — so the finding would come
	// back for ever and the audit would be noise. Same non-idempotence
	// that bit audit-teleport-items in wave 24.
	const canon = (s) => s.toLowerCase()
		.replace(/\([^)]*\)/g, '')       // dose and variant suffixes
		.replace(/[^a-z0-9 ]/g, '')
		.trim()
		.replace(/s$/, '');
	const listed = new Set((entry.items || []).map((it) => canon(it.name || '')));
	const fresh = items.filter((n) => !listed.has(canon(n))
		&& !NOT_CARRIED[`${i}:${canon(n)}`]);
	if (!fresh.length) return;
	const row = { i, id: ids[i], text: textOf(step).slice(0, 56), note: note.replace(/\s+/g, ' ').slice(0, 110), items: fresh };
	if (CARRY.test(note)) seedable.push(row);
	else mentions.push(row);
});

console.log('=== notes that tell you to BRING something the step does not list ===\n');
for (const r of seedable) {
	console.log(`${String(r.i).padStart(3)}  ${r.text}`);
	console.log(`     note: ${r.note}`);
	console.log(`     not listed: ${r.items.join(', ')}\n`);
}
console.log(`${seedable.length} step(s) with a carry instruction whose items are prose only.`);
console.log(`${mentions.length} more note(s) merely MENTION an item — not seeded, they are not instructions.`);

if (showAll) {
	console.log('\n=== mentions (review only) ===');
	for (const r of mentions) {
		console.log(`${String(r.i).padStart(3)}  ${r.text}  ->  ${r.items.join(', ')}`);
	}
}
