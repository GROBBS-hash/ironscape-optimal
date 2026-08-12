#!/usr/bin/env node
/**
 * audit-teleport-landings.mjs — steps that TELL you to teleport, where the
 * teleport actually drops you, and whether the step can tick when it does.
 *
 * WHY. "Cloak tele back to Ardy" put the owner at the Kandarin Monastery,
 * 87 tiles from the Ardougne pin and well outside the 45-tile teleport
 * arrival radius, so the step sat unticked and the NEXT step's navigation
 * never took over (owner, in play 2026-08-12). His call: for a step whose
 * instruction IS the teleport, ticking on the landing is worth more than
 * being strictly right about the place name, because the tick is what
 * hands routing to the next step.
 *
 * This finds the rest of that class instead of meeting them one at a time.
 *
 * IT DOES NOT EDIT ANYTHING. Landing choice needs a human: several items
 * offer more than one destination, and only the player knows which one the
 * guide means.
 *
 * Usage: node tools/audit-teleport-landings.mjs [--all]
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const RES = path.join(repo, 'src/main/resources/com/ironscape');
const showAll = process.argv.includes('--all');

/** Same radius the plugin allows after a teleport (TELEPORT_ARRIVE_RADIUS). */
const TELEPORT_ARRIVE_RADIUS = 45;

const read = (p) => JSON.parse(fs.readFileSync(path.join(RES, p), 'utf8'));
const guide = read('guide/guide_data_oziris.json');
const annRoot = read('annotations/annotations_oziris.json');
const ann = annRoot.annotations || annRoot;
const placesRoot = read('places/places.json');
const places = placesRoot.places || placesRoot;
const teleportItems = read('travel/teleport_items.json');
const landingsRoot = read('places/minigame_landings.json');
const minigameLandings = landingsRoot.landings || landingsRoot;

const steps = [];
for (const ch of guide.chapters) {
	for (const sec of ch.sections) {
		for (const st of sec.steps) steps.push(st);
	}
}
const textOf = (st) => (st.content || []).map((c) => c.text).join('').trim();

// Manifest gives the step ids the annotations are keyed by. Without it we
// cannot tell which steps already carry a target, and reporting a step
// that is already pinned is exactly the noise that gets an audit ignored.
const manifestPath = path.join(
	process.env.USERPROFILE || process.env.HOME, '.runelite/ironscape/guide_manifest.json');
let ids = null;
if (fs.existsSync(manifestPath)) {
	const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
	const variant = manifest.variants && manifest.variants.OZIRIS;
	if (variant && variant.steps && variant.steps.length === steps.length) {
		ids = variant.steps.map((s) => s.id);
	}
}
if (!ids) {
	console.error('No usable guide manifest — run the client once so step ids exist.');
	process.exit(1);
}

/** Standard-spellbook teleports, mirroring TELEPORT_SPELLS in the plugin. */
const SPELL_LANDINGS = {
	varrock: { x: 3213, y: 3424 },
	lumbridge: { x: 3222, y: 3218 },
	falador: { x: 2965, y: 3379 },
	camelot: { x: 2757, y: 3479 },
	ardougne: { x: 2662, y: 3305 },
	watchtower: { x: 2547, y: 3113 },
	house: { x: 0, y: 0 },
};
const HOME_TELEPORT = { x: 3222, y: 3218 };

const dist = (a, b) => Math.round(Math.hypot(a.x - b.x, a.y - b.y));

/** A step's destination, approximating the plugin's targetFor chain. */
function destinationOf(index) {
	const entry = ann[ids[index]];
	if (entry && entry.target) {
		return { point: entry.target, from: 'pinned ⌖' };
	}
	const text = textOf(steps[index]).toLowerCase();
	// Longest display first, the way PlaceManager matches.
	const byLength = Object.entries(places)
		.filter(([, v]) => v && typeof v.x === 'number' && v.type !== 'transport')
		.sort((a, b) => (b[1].display || b[0]).length - (a[1].display || a[0]).length);
	for (const [key, place] of byLength) {
		const display = (place.display || key).toLowerCase();
		if (display.length >= 4 && text.includes(display)) {
			return { point: place, from: `text "${place.display || key}"` };
		}
	}
	const tag = steps[index].metadata && steps[index].metadata.location;
	if (tag) {
		const keyed = places[tag.toLowerCase()];
		if (keyed) return { point: keyed, from: `📍 ${tag}` };
	}
	return null;
}

/** Candidate landings for whatever teleport the step names. */
function landingsFor(text) {
	const lower = text.toLowerCase();
	const found = [];
	if (!/\btele(port)?\b|\btp\b|\btabs?\b|\bcarpet\b/.test(lower)) return found;

	for (const entry of teleportItems) {
		const item = entry.display.includes(':')
			? entry.display.slice(0, entry.display.indexOf(':')).toLowerCase()
			: entry.display.toLowerCase();
		// Match the ITEM the step names ("ardougne cloak", "chronicle"),
		// not the destination, which is the thing we are trying to learn.
		if (item.length >= 6 && lower.includes(item)) {
			found.push({ name: entry.display, point: entry, kind: 'item' });
		}
	}
	for (const [name, point] of Object.entries(SPELL_LANDINGS)) {
		if (name === 'house') continue;
		if (new RegExp(`${name}\\s*(tele|tp|tab)`).test(lower)
			|| new RegExp(`(tele|tp)\\s*(to\\s*)?${name}`).test(lower)) {
			found.push({ name: `${name} teleport`, point, kind: 'spell' });
		}
	}
	if (/home\s*tele/.test(lower)) {
		found.push({ name: 'home teleport', point: HOME_TELEPORT, kind: 'spell' });
	}
	for (const [name, point] of Object.entries(minigameLandings)) {
		if (lower.includes(name.toLowerCase())) {
			found.push({ name: `${name} (minigame)`, point, kind: 'minigame' });
		}
	}
	return found;
}

const findings = [];
const fine = [];
steps.forEach((step, i) => {
	const text = textOf(step);
	const landings = landingsFor(text);
	if (!landings.length) return;
	const destination = destinationOf(i);
	if (!destination) {
		findings.push({ i, text, landings, destination: null, gap: null });
		return;
	}
	// Closest landing wins: if ANY destination of the named item lands you
	// on the step's target, the step already ticks and there is nothing to do.
	let best = null;
	for (const landing of landings) {
		const d = dist(landing.point, destination.point);
		if (best === null || d < best.d) best = { d, landing };
	}
	const row = { i, text, landings, destination, gap: best };
	if (best.d > TELEPORT_ARRIVE_RADIUS) findings.push(row);
	else fine.push(row);
});

console.log('TELEPORT LANDING SWEEP');
console.log('Steps whose instruction is a teleport, where the landing is more than');
console.log(`${TELEPORT_ARRIVE_RADIUS} tiles from what the step routes to — so arriving cannot tick it.\n`);

for (const f of findings) {
	console.log(`${f.i}  ${f.text.slice(0, 68)}`);
	if (!f.destination) {
		console.log('     destination: NONE resolvable');
	} else {
		const p = f.destination.point;
		console.log(`     routes to   ${f.destination.from} (${p.x},${p.y})`);
		console.log(`     lands at    ${f.gap.landing.name} (${f.gap.landing.point.x},${f.gap.landing.point.y}) — ${f.gap.d} tiles away`);
	}
	const others = f.landings.filter((l) => !f.gap || l !== f.gap.landing);
	if (others.length) {
		console.log(`     other destinations of that teleport: ${others.map((o) => o.name).join(', ')}`);
	}
	console.log('');
}

console.log(`${findings.length} step(s) cannot tick on the teleport; ${fine.length} already can.`);
if (showAll && fine.length) {
	console.log('\n--- already fine ---');
	for (const f of fine) {
		console.log(`${f.i}  ${f.text.slice(0, 60)}  (${f.gap.landing.name}, ${f.gap.d} tiles)`);
	}
}
