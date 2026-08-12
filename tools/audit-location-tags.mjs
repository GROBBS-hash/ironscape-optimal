#!/usr/bin/env node
/**
 * audit-location-tags.mjs — every 📍 location tag the guide uses, and
 * whether we can actually resolve it to a point.
 *
 * A step's 📍 tag is one of the routing sources (see preflight), so a tag
 * we cannot resolve is a step with nowhere to go — and unlike a grind
 * step, where a pin would be arbitrary anyway, these name a REAL place
 * that simply is not in places.json. Wave 17 closed ten of these by hand;
 * this measures what is left instead of finding them one play session at
 * a time.
 *
 * Mirrors PlaceManager's lookup: exact key, then the "loose" match that
 * strips leading directions and articles.
 *
 * Usage: node tools/audit-location-tags.mjs
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const RES = path.join(repo, 'src/main/resources/com/ironscape');

const guide = JSON.parse(fs.readFileSync(
	path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const placesRoot = JSON.parse(fs.readFileSync(
	path.join(RES, 'places/places.json'), 'utf8'));
const places = placesRoot.places || placesRoot;

const norm = (s) => s.toLowerCase().trim();
const keys = new Set(Object.keys(places).map(norm));
const displays = new Set(Object.values(places)
	.map((p) => (p && p.display ? norm(p.display) : null)).filter(Boolean));

/** PlaceManager.getLoose strips a leading direction word and articles. */
const loosen = (s) => norm(s)
	.replace(/^(north|south|east|west|northern|southern|eastern|western|upper|lower)\s+(of\s+)?/, '')
	.replace(/^the\s+/, '');

function resolves(tag) {
	const candidates = [norm(tag), loosen(tag)];
	for (const c of candidates) {
		if (keys.has(c) || displays.has(c)) return true;
	}
	// Substring: PlaceManager matches a place NAME inside the tag text.
	for (const c of candidates) {
		for (const k of keys) {
			if (k.length >= 5 && c.includes(k)) return true;
		}
	}
	return false;
}

const tagCounts = new Map();
for (const ch of guide.chapters) {
	for (const sec of ch.sections) {
		for (const st of sec.steps) {
			const tag = st.metadata && st.metadata.location;
			if (!tag) continue;
			tagCounts.set(tag, (tagCounts.get(tag) || 0) + 1);
		}
	}
}

const missing = [];
let ok = 0;
for (const [tag, count] of tagCounts) {
	if (resolves(tag)) ok++;
	else missing.push({ tag, count });
}
missing.sort((a, b) => b.count - a.count);

console.log(`${tagCounts.size} distinct 📍 tags — ${ok} resolve, ${missing.length} do not\n`);
for (const m of missing) {
	console.log(`  ${String(m.count).padStart(3)} step(s)   ${m.tag}`);
}
console.log('\n"Various" and similar are not places and want no pin — everything else');
console.log('here is a real location the guide names that we cannot route to.');
