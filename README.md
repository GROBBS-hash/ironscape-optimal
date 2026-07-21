# IRONSCAPE Optimal — RuneLite plugin

The [BRUHsailer](https://osrsper.github.io/BRUHsailer/) efficient ironman
guide as a step-by-step side panel inside RuneLite, with automatic step
completion, an on-screen step overlay with live item/level counts,
teleport click-path highlights, click-to-hop world links, a bank filter
for upcoming items, and Shortest Path navigation.

**Every step of guide content in this plugin is the work of the
BRUHsailer authors** — this plugin is only a different way to read and
follow it. If you find it useful, the credit belongs to them.

## Credits

- **Guide written by [So Iron BRUH](https://www.youtube.com/@SoIronBRUH)
  & ParasailerOSRS** — the BRUHsailer efficient ironman guide, ~1000
  steps of routing that this plugin merely displays.
- **Web adaptation by kyyznn** ([umkyzn/BRUHsailer](https://github.com/umkyzn/BRUHsailer)),
  **improved and maintained by Jesper** ([osrsper](https://github.com/osrsper)) —
  the structured guide data this plugin ingests comes from their site,
  <https://osrsper.github.io/BRUHsailer/>.
- Navigation integrates with [Shortest Path](https://github.com/Skretzo/shortest-path)
  by Skretzo (separate plugin, install it from the Plugin Hub).

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
the bank to items your current guide section still needs.

```
gradlew build
```

compiles and runs tests.

## Project layout

| Path | What |
| --- | --- |
| `src/main/java/com/bruhsailer/` | The plugin itself |
| `src/test/java/.../BruhsailerPluginTest.java` | Dev launcher (boots a real client) |
| `src/main/resources/.../annotations/annotations.json` | Bundled step annotations (community defaults) |
| `tools/` | Node scripts for drafting annotations |
| `runelite-plugin.properties` | Plugin Hub metadata |

## Annotating steps

Annotations make the plugin smarter but are always optional.

- **Locations:** click the ⌖ button on any step while standing at the right
  spot in game. Saved to `~/.runelite/bruhsailer/annotations.json`.
- **Skill requirements:** `node tools/extract-annotations.mjs` drafts
  candidates from the guide text, then `node tools/review-annotations.mjs`
  walks you through approving them (y/n per step). Approved requirements go
  into the bundled annotations file — rebuild to pick them up. Steps whose
  requirement you meet get ticked off automatically in game.
