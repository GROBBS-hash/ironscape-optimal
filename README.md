# IRONSCAPE Optimal — RuneLite plugin

The [Ironman Efficiency Guide](https://ironman.guide/) as a step-by-step
side panel inside RuneLite: tick-off steps with automatic completion
detection (skill levels, quest progress, mid-quest checkpoints, item
counts), an on-screen step overlay with live item/level counts, item
sprites and have/need badges against your bank, location and quest chips
per step, click-to-navigate place links (via the Shortest Path plugin),
click-to-hop world links, and a bank filter for upcoming items.

## Credits

- **Guide content by [Oziris](https://twitter.com/ozirislol) and the
  [ironman.guide](https://ironman.guide/) community** (the v4 "Enhanced
  2026" edition) — used with their permission. Every step this plugin
  displays is their routing work; if the guide helps you, the credit is
  theirs.
- Navigation integrates with [Shortest Path](https://github.com/Skretzo/shortest-path)
  by Skretzo (separate plugin, install it from the Plugin Hub).
- Mid-quest checkpoint values were cross-checked against
  [Quest Helper](https://github.com/Zoinkwiz/quest-helper)'s open-source
  quest data.

## Development

Requirements: JDK 11 or newer (17 works). Gradle is not needed — the
wrapper downloads it.

```
gradlew run
```

launches a RuneLite client with the plugin loaded. Log in on any account
and enable **IRONSCAPE Optimal** in the plugin list (wrench icon) if it
isn't already on.

Tip: the button in the bank UI (or typing `bruh` in bank search) filters
the bank to items your upcoming guide steps still need.

```
gradlew build
```

compiles and runs tests.

## Project layout

| Path | What |
| --- | --- |
| `src/main/java/com/ironscape/` | The plugin |
| `src/test/java/.../IronscapePluginTest.java` | Dev launcher (boots a real client) |
| `src/main/resources/.../guide/guide_data_oziris.json` | Bundled guide data (scraped, see tools) |
| `src/main/resources/.../annotations/annotations_oziris.json` | Bundled step annotations |
| `tools/` | Node scripts: guide scraper, place seeding |
| `runelite-plugin.properties` | Plugin Hub metadata |

## Tools

- `node tools/scrape-oziris.mjs` — refreshes the bundled guide from
  ironman.guide (their pages embed author-structured step data: text,
  locations, quests, skill goals, item lists, notes). Hand-authored
  annotation keys (quest checkpoints, captured targets) survive the
  refresh.
- `node tools/seed-places.mjs [--quests|--locations|--links|--pois]` —
  seeds `places.json` (the clickable place-name links) from the OSRS
  Wiki.
- `node tools/seed-item-ids.mjs` — seeds `item_ids.json` (item sprites
  for untradeables) from the OSRS Wiki. Regenerate the input list
  first: compile tests, then run `PrintItemNamesProbe` redirecting its
  output to `tools/item-names.txt`.

## Annotating steps

Annotations make the plugin smarter but are always optional.

- **Locations:** click the ⌖ button on any step while standing at the
  right spot in game. Saved to `~/.runelite/ironscape/annotations.json`.
- **Mid-quest checkpoints:** requirements like `{"varbit": 5619, "value": 5}`
  tick a step when a quest reaches a certain point ("do the quest until
  the orb"). See `PrintSubIdProbe` for finding step ids.

## License

BSD 2-Clause (see [LICENSE](LICENSE)). Guide content by Oziris & the
ironman.guide community, bundled with permission.
