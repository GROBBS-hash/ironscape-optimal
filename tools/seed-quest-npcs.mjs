#!/usr/bin/env node
/**
 * seed-quest-npcs.mjs — the NPCs each quest actually involves, by ID.
 *
 * WHY. When a step names no NPC, the plugin outlines whoever stands
 * nearest the step's target. That fallback has now crowned rats, a
 * Zamorak crafter, a Market Guard, a Master Farmer and the owner's CAT
 * (which wore the Jungle Potion quest icon, 2026-08-12). Each was patched
 * one at a time. The owner's question was the right one: the names are
 * listed on the wiki and in Quest Helper, so why are we guessing?
 *
 * Because we only ever seeded the quest GIVER — one NPC per quest, from
 * the wiki infobox. Nobody indexed the rest, on the reasoning that Quest
 * Helper owns mid-quest guidance. But the fallback still runs, and it has
 * nothing to check itself against.
 *
 * BY ID, NOT BY NAME. Quest Helper writes NPCs as `NpcID.TRUFITUS`, and
 * at runtime every scene NPC carries an id. Matching ids skips the whole
 * class of name faults this project keeps hitting — articles ("The Lady of
 * the Lake"), plurals, species suffixes, a name sitting inside a place
 * name. An id is the same id in both places or it is not.
 *
 * Constant -> id comes from javap on RuneLite's own gameval NpcID, the
 * same technique used for the varbit work, so the mapping is the client's
 * rather than a guess.
 *
 * Usage:
 *   node tools/seed-quest-npcs.mjs            # report only
 *   node tools/seed-quest-npcs.mjs --apply    # write the resource
 *   node tools/seed-quest-npcs.mjs --quest "Jungle Potion"
 */

import fs from 'fs';
import path from 'path';
import os from 'os';
import { execFileSync } from 'child_process';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const RES = path.join(repo, 'src/main/resources/com/ironscape');
const CACHE = path.join(here, '.qh-cache');
const BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/'
	+ 'src/main/java/com/questhelper/helpers/quests/';

const args = process.argv.slice(2);
const apply = args.includes('--apply');
const onlyQuest = args.indexOf('--quest') >= 0 ? args[args.indexOf('--quest') + 1] : null;

// ---- 1. NpcID constant -> numeric id, from the client's own class -------
function npcIdConstants() {
	const cacheFile = path.join(CACHE, 'npc-id-constants.tsv');
	if (fs.existsSync(cacheFile)) {
		const map = new Map();
		for (const line of fs.readFileSync(cacheFile, 'utf8').split('\n')) {
			const [name, id] = line.split('\t');
			if (name && id) map.set(name, Number(id));
		}
		return map;
	}
	const jars = [];
	const walk = (dir, depth) => {
		if (depth > 8) return;
		let entries = [];
		try {
			entries = fs.readdirSync(dir, { withFileTypes: true });
		} catch {
			return;
		}
		for (const e of entries) {
			const full = path.join(dir, e.name);
			if (e.isDirectory()) walk(full, depth + 1);
			else if (/^runelite-api.*\.jar$/.test(e.name) && !/sources|javadoc/.test(e.name)) jars.push(full);
		}
	};
	walk(path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1/net.runelite'), 0);
	if (!jars.length) {
		console.error('Could not find runelite-api jar in the gradle cache — build once first.');
		process.exit(1);
	}
	const out = execFileSync('javap',
		['-constants', '-classpath', jars[0], 'net.runelite.api.gameval.NpcID'],
		{ encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
	const map = new Map();
	const lines = [];
	for (const m of out.matchAll(/public static final int (\w+) = (\d+);/g)) {
		map.set(m[1], Number(m[2]));
		lines.push(`${m[1]}\t${m[2]}`);
	}
	fs.mkdirSync(CACHE, { recursive: true });
	fs.writeFileSync(cacheFile, lines.join('\n'));
	return map;
}

// ---- 2. which quests the guide actually mentions ------------------------
const guide = JSON.parse(fs.readFileSync(
	path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annRoot = JSON.parse(fs.readFileSync(
	path.join(RES, 'annotations/annotations_oziris.json'), 'utf8'));
const ann = annRoot.annotations || annRoot;

const quests = new Set();
for (const ch of guide.chapters) {
	for (const sec of ch.sections) {
		for (const st of sec.steps) {
			const q = st.metadata && st.metadata.quest;
			if (q) quests.add(q.trim());
		}
	}
}
for (const entry of Object.values(ann)) {
	if (entry.quest) quests.add(String(entry.quest).trim());
}

// ---- 3. Quest Helper source per quest -----------------------------------
const UA = { 'User-Agent': 'ironscape-dev tooling' };

/**
 * GitHub directory listing, cached.
 *
 * Through `gh`, not plain fetch: unauthenticated API calls are capped at
 * 60/hour, and listing a directory per quest blew through that and took
 * the run from 96 quests indexed to 20 — a failure that looks exactly
 * like "Quest Helper has no file for this quest". Raw file fetches have
 * no such cap, which is why they stay on fetch below.
 */
function listing(apiPath) {
	fs.mkdirSync(CACHE, { recursive: true });
	const file = path.join(CACHE, 'list_' + apiPath.replace(/[:/?=&]+/g, '_') + '.json');
	if (fs.existsSync(file)) {
		const text = fs.readFileSync(file, 'utf8');
		return text.startsWith('404') ? [] : JSON.parse(text);
	}
	let body;
	try {
		body = execFileSync('gh', ['api', apiPath],
			{ encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
	} catch {
		body = '404';
	}
	fs.writeFileSync(file, body);
	return body.startsWith('404') ? [] : JSON.parse(body);
}

const API = 'repos/Zoinkwiz/quest-helper/contents/'
	+ 'src/main/java/com/questhelper/helpers/';
const questDirs = listing(API + 'quests').map((e) => e.name);
const miniDirs = listing(API + 'miniquests').map((e) => e.name);

const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
/** Trailing roman numeral: "Dragon Slayer I" is filed as dragonslayer. */
const dropNumeral = (s) => s.replace(/(i{1,3}|iv|v)$/, '');

/**
 * Which Quest Helper directory holds this quest.
 *
 * Guessing the path from the quest's name got 96 of 114; the misses were
 * all naming, not missing data ("Romeo & Juliet" is romeoandjuliet,
 * "Vampire Slayer" is vampyreslayer, "Dragon Slayer I" is dragonslayer).
 * So resolve against the real listing, and prefer an EXACT normalised
 * match before any looser one — "Dragon Slayer II" must not fall back to
 * dragonslayer just because it starts with it.
 */
/**
 * Hand-authored, NOT fuzzy. Each is a real difference between the guide's
 * name and Quest Helper's folder, and loosening the matcher to catch them
 * would risk pairing "Dragon Slayer I" with dragonslayerii — the same trap
 * the RuneLite quest-name aliases were written for.
 */
const DIR_ALIASES = {
	"Black Knights' Fortress": 'blackknightfortress',   // singular Knight
	'Fairytale II - Cure a Queen': 'fairytaleii',        // fairytalei also prefixes it
	'Romeo & Juliet': 'romeoandjuliet',
	'Vampire Slayer': 'vampyreslayer',                   // renamed to Vampyre
};

function resolveDir(name) {
	if (DIR_ALIASES[name]) {
		const dir = DIR_ALIASES[name];
		return { dir, kind: questDirs.includes(dir) ? 'quests' : 'miniquests' };
	}
	const n = norm(name);
	for (const dirs of [questDirs, miniDirs]) {
		const kind = dirs === questDirs ? 'quests' : 'miniquests';
		if (dirs.includes(n)) return { dir: n, kind };
		const bare = dropNumeral(n);
		// A numeral-stripped name may collide with the sequel's directory,
		// so only accept it when the sequel is not what we asked for.
		if (bare !== n && dirs.includes(bare)) return { dir: bare, kind };
		// "Recipe for Disaster (Evil Dave)" -> recipefordisaster
		const prefix = dirs.filter((d) => n.startsWith(d) && d.length >= 8);
		if (prefix.length === 1) return { dir: prefix[0], kind };
		// "Hand in the Sand" -> thehandinthesand
		const article = dirs.filter((d) => d === 'the' + n);
		if (article.length === 1) return { dir: article[0], kind };
	}
	return null;
}

/** Raw file fetch, cached. No API rate limit applies here. */
async function raw(url) {
	fs.mkdirSync(CACHE, { recursive: true });
	const file = path.join(CACHE, url.replace(/[:/]+/g, '_'));
	if (fs.existsSync(file)) {
		const text = fs.readFileSync(file, 'utf8');
		return text.startsWith('404') ? null : text;
	}
	const res = await fetch(url, { headers: UA });
	const body = res.ok ? await res.text() : '404';
	fs.writeFileSync(file, body);
	return res.ok ? body : null;
}

async function source(name) {
	// FAST PATH: the guessed path, which already resolved 96 of 114 and
	// costs one un-throttled raw fetch. Directory listings are only worth
	// spending on what it misses.
	const guessDir = norm(name);
	const guessFile = name.replace(/[^A-Za-z0-9 ]/g, '').split(/\s+/)
		.filter(Boolean)
		.map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
	const guessed = await raw('https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/'
		+ `src/main/java/com/questhelper/helpers/quests/${guessDir}/${guessFile}.java`);
	if (guessed) return guessed;

	const found = resolveDir(name);
	if (!found) return null;
	const base = `https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/`
		+ `src/main/java/com/questhelper/helpers/${found.kind}/${found.dir}/`;
	const files = listing(API + `${found.kind}/${found.dir}`)
		.filter((e) => e.name.endsWith('.java')).map((e) => e.name);
	if (!files.length) return null;
	// The quest's own class is the one named like its directory; the rest
	// are its requirement and step helpers, whose NpcIDs belong to it too,
	// so read them ALL rather than pick.
	let combined = '';
	for (const f of files) {
		const url = base + f;
		const cached = path.join(CACHE, url.replace(/[:/]+/g, '_'));
		let text;
		if (fs.existsSync(cached)) {
			text = fs.readFileSync(cached, 'utf8');
		} else {
			const res = await fetch(url, { headers: UA });
			text = res.ok ? await res.text() : '404';
			fs.writeFileSync(cached, text);
		}
		if (!text.startsWith('404')) combined += '\n' + text;
	}
	return combined || null;
}

const constants = npcIdConstants();
const targets = onlyQuest ? [onlyQuest] : [...quests].sort();

const result = {};
const missingFile = [];
const unresolved = new Map();

for (const quest of targets) {
	const java = await source(quest);
	if (!java) {
		missingFile.push(quest);
		continue;
	}
	const ids = new Set();
	const names = new Set();
	for (const m of java.matchAll(/NpcID\.(\w+)/g)) {
		const constant = m[1];
		names.add(constant);
		const id = constants.get(constant);
		if (id === undefined) {
			// A constant the bundled client does not know: QH tracks master,
			// we pin a release. Recorded rather than dropped, because a
			// silent drop looks identical to "this quest has no NPCs".
			if (!unresolved.has(quest)) unresolved.set(quest, new Set());
			unresolved.get(quest).add(constant);
			continue;
		}
		ids.add(id);
	}
	if (ids.size) result[quest] = [...ids].sort((a, b) => a - b);
	console.log(`${quest.padEnd(38)} ${String(ids.size).padStart(3)} npc id(s)`
		+ (unresolved.has(quest) ? `  (${unresolved.get(quest).size} unknown to this client)` : ''));
}

console.log(`\n${Object.keys(result).length} of ${targets.length} quests indexed`);
console.log(`${Object.values(result).reduce((n, a) => n + a.length, 0)} npc ids total`);
if (missingFile.length) {
	console.log(`\nNo Quest Helper file for ${missingFile.length}:`);
	for (const q of missingFile) console.log(`  ${q}`);
}
if (unresolved.size) {
	let n = 0;
	for (const set of unresolved.values()) n += set.size;
	console.log(`\n${n} constant(s) unknown to the bundled client (QH tracks master) — skipped.`);
}

if (!apply) {
	console.log('\n(report only — pass --apply to write the resource)');
	process.exit(0);
}

const out = path.join(RES, 'places/quest_npcs.json');
fs.writeFileSync(out, JSON.stringify({ questNpcs: result }, null, ' ') + '\n');
console.log(`\nwrote ${out.replace(repo + path.sep, '')}`);
