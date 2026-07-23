# IRONSCAPE Optimal — RuneLite plugin

This plugin is based on the [Ironman Efficiency Guide](https://ironman.guide/) as a step-by-step
side panel inside RuneLite: tick-off steps with automatic completion
detection (skill levels, quest progress, mid-quest checkpoints, item
counts), an on-screen step overlay with live item/level counts, item
sprites and have/need badges against your bank, location and quest chips
per step, click-to-navigate place links (via the Shortest Path plugin),
click-to-hop world links, and a bank filter for upcoming items. Contrary to other plugins, 
This one also has a built in auto update feature, meaning it will be upto date with the latest findings
from the team at Ironman.guide.

If this sees use, I'm happy to continue improving, adding and fixing bugs.

## What it looks like

![The guide panel, step overlay and auto-navigation in action](docs/panel-navigation.png)

One moment, most of the plugin: the **side panel** tracks the guide with
tickable steps, live requirement badges (`fletching 20/15 ·
construction 21/20`, `cash 20549/200000`) and per-step location/note
chips; the **step overlay** (top-left) shows the current action and its
counts in-game; **auto-navigation** has already handed the route to the
boat to Shortest Path (cyan trail); and the step will **tick itself**
when the travel completes. Steps auto-complete off skill levels, quest
state, item counts, teleports, arrivals and mid-quest checkpoints — the
checkbox is always there when detection can't know.

Also in the plugin (screenshots coming): a Quest Helper-style **bank
view** that groups every upcoming step's items into sections with
green/red have/need counts (the real, withdrawable bank widgets);
**shopkeeper outlines** with the item you're buying floating overhead;
teleport click-path highlights; and a quest handoff that stands our
navigation down while Quest Helper guides an in-progress quest.

## Credits

- **Guide content by [Oziris](https://twitter.com/ozirislol) and the
  [ironman.guide](https://ironman.guide/) community** (the v4 "Enhanced
  2026" edition) — used with their permission. Thanks to them for maintaining the guide!
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
