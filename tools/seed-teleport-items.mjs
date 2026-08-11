#!/usr/bin/env node
/**
 * seed-teleport-items.mjs — build the teleport-item index from Shortest
 * Path's own transport data.
 *
 * WHY THIS IS NOT A WIKI SCRAPE. The first-leg hint knew minigame
 * teleports, standard-spellbook spells, the free home teleport and the
 * Chronicle, and nothing else — so it kept offering a Varrock teleport
 * for a West Ardougne target while an Ardougne cloak sat in the bag that
 * lands next door (owner, 2026-08-11). The obvious plan was to wiki-search
 * every teleport item and index it by hand.
 *
 * That work is already done and maintained: Shortest Path ships
 * `teleportation_items.tsv`, one row per DESTINATION, carrying the item
 * ids that provide it, the landing tile, the menu option to pick, and the
 * unlock/charge conditions as varbits. 319 rows covering diary cloaks,
 * teleport jewellery, tablets, max capes, Kharedst's memoirs and the rest.
 * Re-running this picks up their fixes for free.
 *
 * Their format is documented in docs/Transport-TSV-format.md; the columns
 * and operators here follow it rather than being inferred from the data,
 * because a column guessed right on the rows you looked at is a column
 * that breaks on the ones you did not.
 *
 * Usage:
 *   node tools/seed-teleport-items.mjs            # report only
 *   node tools/seed-teleport-items.mjs --apply    # write the resource
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const SRC = path.join(here, '.sp-cache', 'teleportation_items.tsv');
const OUT = path.join(repo, 'src/main/resources/com/ironscape/travel/teleport_items.json');

const apply = process.argv.includes('--apply');

// Column order from the file's own header comment, which matches
// docs/Transport-TSV-format.md.
const COL = {
	destination: 0,
	menu: 1,
	skills: 2,
	items: 3,
	quests: 4,
	duration: 5,
	display: 6,
	consumable: 7,
	wilderness: 8,
	varbits: 9,
	varplayers: 10,
};

/** "2607 3221 0" -> {x,y,plane}. */
function parsePoint(value) {
	const parts = value.trim().split(/\s+/);
	if (parts.length < 3) return null;
	const [x, y, plane] = parts.map(Number);
	if (![x, y, plane].every(Number.isFinite)) return null;
	return { x, y, plane };
}

/**
 * Items column -> a flat list of item ids that each, on their own, provide
 * this teleport.
 *
 * The format supports OR groups (`||`) and AND groups (`&&`). For teleport
 * items the real shape is OR — "any tier of Ardougne cloak", "any charge
 * of games necklace". An AND group would mean "carry both", which no
 * teleport item row uses; if one ever appears we DROP the row rather than
 * pretend, because treating an AND as an OR would claim a teleport the
 * player cannot make.
 */
function parseItems(value) {
	const token = value.replace(/\s+/g, '').toUpperCase();
	if (!token) return { ids: [], skipped: 'no items' };
	if (token.replace(/\|\|/g, '|').includes('&')) {
		return { ids: [], skipped: 'AND group (needs several items at once)' };
	}
	const ids = [];
	for (const part of token.replace(/\|\|/g, '|').split('|')) {
		const id = Number(part.split('=')[0]);
		if (!Number.isFinite(id)) {
			return { ids: [], skipped: `non-numeric item token "${part}"` };
		}
		ids.push(id);
	}
	return { ids, skipped: null };
}

/**
 * Varbit/VarPlayer clauses: semicolon-separated `ID<op>VALUE`.
 *
 * Operators per the format doc: `=` equals, `>` greater than, `<` less
 * than, `&` bitmask, `@` a real-time countdown in minutes. `@` is a
 * cooldown rather than an unlock, so it is kept and labelled — the Java
 * side decides what to do with it.
 */
function parseVars(value) {
	const clauses = [];
	for (const raw of value.split(';')) {
		const clause = raw.trim();
		if (!clause) continue;
		const match = clause.match(/^(\d+)\s*([=><&@])\s*(\d+)$/);
		if (!match) return { clauses: [], bad: clause };
		clauses.push({ id: Number(match[1]), op: match[2], value: Number(match[3]) });
	}
	return { clauses, bad: null };
}

function parseSkills(value) {
	const skills = [];
	for (const raw of value.split(';')) {
		const clause = raw.trim();
		if (!clause) continue;
		// "83 Farming" — exactly one space, per the format doc.
		const match = clause.match(/^(\d+)\s+(\w+)$/);
		if (!match) return { skills: [], bad: clause };
		skills.push({ level: Number(match[1]), skill: match[2].toUpperCase() });
	}
	return { skills, bad: null };
}

const lines = fs.readFileSync(SRC, 'utf8').split(/\r?\n/);
const entries = [];
const skipped = [];

for (const line of lines) {
	if (!line.trim() || line.trimStart().startsWith('#')) continue;
	const f = line.split('\t');
	const display = (f[COL.display] || '').trim();
	const destination = parsePoint(f[COL.destination] || '');
	if (!destination) {
		skipped.push({ display, why: 'no destination tile' });
		continue;
	}
	const { ids, skipped: itemsWhy } = parseItems(f[COL.items] || '');
	if (itemsWhy) {
		skipped.push({ display, why: itemsWhy });
		continue;
	}
	const varbits = parseVars(f[COL.varbits] || '');
	const varplayers = parseVars(f[COL.varplayers] || '');
	const skills = parseSkills(f[COL.skills] || '');
	if (varbits.bad || varplayers.bad || skills.bad) {
		skipped.push({ display, why: `unparsed condition "${varbits.bad || varplayers.bad || skills.bad}"` });
		continue;
	}
	const quests = (f[COL.quests] || '').split(';').map(q => q.trim()).filter(Boolean);
	const consumable = /^(t|yes)$/i.test((f[COL.consumable] || '').trim());
	const wilderness = Number((f[COL.wilderness] || '').trim());

	const entry = { display, itemIds: ids, ...destination };
	if (consumable) entry.consumable = true;
	if (Number.isFinite(wilderness) && wilderness > 0) entry.maxWilderness = wilderness;
	if (varbits.clauses.length) entry.varbits = varbits.clauses;
	if (varplayers.clauses.length) entry.varplayers = varplayers.clauses;
	if (skills.skills.length) entry.skills = skills.skills;
	if (quests.length) entry.quests = quests;
	entries.push(entry);
}

// Report before writing: what got in, and every row that did not, with the
// reason. A seeder that silently drops rows is how you end up believing a
// teleport is indexed when it is not.
console.log(`teleport items: ${entries.length} destinations from ${SRC.replace(repo + path.sep, '')}`);
const distinctItems = new Set(entries.flatMap(e => e.itemIds));
console.log(`  ${distinctItems.size} distinct item ids provide them`);
console.log(`  ${entries.filter(e => e.varbits || e.varplayers).length} carry an unlock/charge condition`);
console.log(`  ${entries.filter(e => e.quests).length} need a quest, ${entries.filter(e => e.skills).length} need a skill level`);
console.log(`  ${entries.filter(e => e.consumable).length} consume a charge`);

if (skipped.length) {
	console.log(`\n  ${skipped.length} row(s) NOT indexed:`);
	for (const s of skipped) console.log(`    ${s.display || '(no label)'} — ${s.why}`);
} else {
	console.log('\n  every row indexed');
}

if (!apply) {
	console.log('\n(report only — pass --apply to write the resource)');
	process.exit(0);
}

fs.writeFileSync(OUT, JSON.stringify(entries, null, '\t') + '\n');
console.log(`\nwrote ${OUT.replace(repo + path.sep, '')}`);
