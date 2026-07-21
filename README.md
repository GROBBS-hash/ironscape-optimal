# IRONSCAPE Optimal — RuneLite plugin

The [BRUHsailer](https://osrsper.github.io/BRUHsailer/) efficient ironman
guide as a step-by-step side panel inside RuneLite, with automatic step
completion, live item tracking, and Shortest Path navigation.

## Credits

- **Guide:** So Iron BRUH & ParasailerOSRS
- **Web adaptation:** kyyznn, improved by Jesper ([osrsper](https://github.com/osrsper))
- Upstream guide data: https://github.com/umkyzn/BRUHsailer

## Development

Requirements: JDK 11 or newer (17 works). Gradle is not needed — the
wrapper downloads it.

```
gradlew run
```

launches a RuneLite client with the plugin loaded. Log in on any account
and enable **IRONSCAPE Optimal** in the plugin list (wrench icon) if it
isn't already on.

Tip: type `bruh` in the bank search box to filter the bank to items the
guide needs soon.

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
