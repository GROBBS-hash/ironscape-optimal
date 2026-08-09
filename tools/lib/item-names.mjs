// Shared item-name checking for audit-goals' item_ids section.
//
// WHY THIS IS NOT A CONSTANT-NAME CHECK ANY MORE. The old version asked
// whether each item_ids key looked like the id's GAMEVAL CONSTANT, and it
// answered "0 suspicious" on the night six real bugs were found by hand.
// Item 1017's constant is BLACKWIZHAT, which reads exactly like a real
// item name — so checking it CONFIRMED the wrong answer. The item's
// actual display name is "Wizard hat". The constant is a symbol someone
// chose; the display name is what the plugin matches carried items
// against, and only the second one decides whether a count works.
//
// THE DEEPER FAULT was the allow-list. "black wizards hat",
// "green d'hide top" and "armor seeds" were all listed as VERIFIED, hand
// checked against the wiki in an earlier sweep. That sweep answered a
// real question — is the ID right? — and all three ids ARE right, which
// is exactly why their badges show a correct sprite. But recording the
// answer as blanket approval silenced a second, different question:
// will the NAME ever match what the player is carrying? So these files
// now separate the two, and an id-level exemption can no longer suppress
// a name-level defect.
//
// Bridging is a PRESENCE check against ItemTracker's own COLLOQUIAL map,
// not a reimplementation of the alias chain — deliberately, because a
// second copy of that logic would drift from the first and start lying.
// Same reasoning as audit-place-spans mirroring PlaceManager.

import fs from 'node:fs';

/**
 * A faithful mirror of ItemTracker.canonical(). Kept word-for-word with
 * it on purpose: a looser copy invented thirteen findings on its first
 * run ("woad leaves", "wizard mind bombs") because it skipped the
 * per-word singularisation that makes those match in the plugin. If that
 * method changes, change this — the whole check is worthless if the two
 * disagree, and it fails LOUDLY (false alarms) rather than quietly.
 */
export const canonical = (s) => s.toLowerCase()
  .replace(/\(\d+\)/g, '')
  .replace(/[^a-z0-9 ]/g, '')
  .split(/\s+/)
  .filter(Boolean)
  .map((word) => {
    if (word.length > 4 && word.endsWith('ves')) {
      return word.slice(0, -3) + 'f';
    }
    if (word.length > 3 && word.endsWith('fe')) {
      return word.slice(0, -1);
    }
    if (word.length > 3 && word.endsWith('s') && !word.endsWith('ss')) {
      return word.slice(0, -1);
    }
    return word;
  })
  .join(' ');

/** The colloquial->real-name pairs ItemTracker actually ships. */
export function colloquialKeys(itemTrackerSource) {
  const start = itemTrackerSource.indexOf('COLLOQUIAL = Map.ofEntries(');
  if (start < 0) {
    throw new Error('COLLOQUIAL map not found in ItemTracker.java — '
      + 'this check mirrors it and must be updated with it');
  }
  const region = itemTrackerSource.slice(start);
  const end = region.indexOf('\n\t/**');
  const body = end > 0 ? region.slice(0, end) : region;
  const map = new Map();
  for (const m of body.matchAll(/Map\.entry\("([^"]+)",\s*"([^"]+)"\)/g)) {
    map.set(m[1], m[2]);
  }
  return map;
}

/**
 * Can the alias chain plausibly get from the guide's word to the real
 * item name? Mirrors only the DOCUMENTED, stable rules in aliases():
 * whole-word and first-word plurals, and the "rune"/"ore" shorthands.
 * Anything cleverer belongs in COLLOQUIAL, where it is visible.
 */
export function bridged(key, realName, colloquial) {
  const k = canonical(key);
  const real = canonical(realName);
  if (k === real) {
    return true;
  }
  const mapped = colloquial.get(key);
  if (mapped && canonical(mapped) === real) {
    return true;
  }
  // A colloquial that points somewhere else entirely is still a bridge —
  // the plugin rewrites the key before matching, so the id is what would
  // be stale, and that is the id check's business, not this one.
  if (mapped) {
    return true;
  }
  const candidates = new Set([k, k + 's', k + ' rune', k + ' ore']);
  if (k.endsWith('s')) {
    const singular = k.slice(0, -1);
    candidates.add(singular);
    candidates.add(singular + ' rune');
    candidates.add(singular + ' ore');
  }
  const words = k.split(' ');
  if (words.length > 1 && words[0].endsWith('s')) {
    candidates.add([words[0].slice(0, -1), ...words.slice(1)].join(' '));
  }
  return candidates.has(real);
}

/**
 * Names whose alias chain can never match the item they point at.
 *
 * Reads build/item-aliases.tsv — what ItemTracker ACTUALLY produces —
 * and applies exactly the test the tracker applies: an alias equal to
 * the item's name, or equal once both sides are canonicalised. Shared so
 * the audit and the review UI cannot drift about what counts as broken.
 */
export function flaggedNames(aliasTsvText, liveNames) {
  const flagged = [];
  let untradeable = 0;
  for (const line of aliasTsvText.split('\n')) {
    const [key, id, aliases, canonicalAliases] = line.split('\t');
    if (!key || !id) {
      continue;
    }
    const real = liveNames.get(Number(id));
    if (!real) {
      untradeable++;
      continue;
    }
    const lower = real.toLowerCase();
    if (aliases.split('|').some((a) => a.toLowerCase() === lower)
      || canonicalAliases.split('|').includes(canonical(real))) {
      continue;
    }
    flagged.push({ key, id: Number(id), real, aliases: aliases.split('|') });
  }
  return { flagged, untradeable };
}

/**
 * id -> display name, for EVERY item. Cached on disk.
 *
 * RuneLite's own cache dump, not the wiki's price mapping. The price
 * mapping only lists TRADEABLES, which left 67 of our ids unjudged — and
 * those are exactly the ones that most need judging, since an untradeable
 * has no market page to sanity-check it against. This file has 21,339
 * entries including them, and it is the same cache the client reads, so
 * the audit and the plugin cannot disagree about what an item is called.
 */
export async function liveItemNames(cacheFile) {
  if (fs.existsSync(cacheFile)) {
    return new Map(JSON.parse(fs.readFileSync(cacheFile, 'utf8')));
  }
  const res = await fetch('https://static.runelite.net/cache/item/names.json',
    { headers: { 'user-agent': 'ironscape-optimal audit tool' } });
  if (!res.ok) {
    return null;
  }
  const pairs = Object.entries(await res.json()).map(([id, name]) => [Number(id), name]);
  fs.writeFileSync(cacheFile, JSON.stringify(pairs));
  return new Map(pairs);
}
