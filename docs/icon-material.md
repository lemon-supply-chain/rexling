# Program Tile Material Design

Design and implementation notes for the program tiles used in the tab bar, pane
captions, floating dock and session header. The catalog contains 174 compiled
tile artworks; unknown or fallback programs use the same shared skeuomorphic
card chrome (`SkeuomorphicCardChrome`) around a procedural gradient plate.

Scope: unified SuperBlockIcons-style skeuomorphic card stack across all tile
sizes (compact 18–27dp and detailed 34dp+), matching the macOS `Rex Beta.app`
`CardArtwork` / `card-template.svg` material language.

---

## 1. Constraints

| Constraint | Consequence |
| --- | --- |
| Tiles render at 18–27dp and 34dp | Every size uses the full card chrome, rasterized to an exact pixel-sized bitmap cache when unrotated. |
| Vector catalog supports paths, solid fills and linear/radial gradients only | Runtime `SkeuomorphicCardChrome` draws the shared squircle outer shadow, theme-matched outer rim (white in light theme, `#2A2A2E` charcoal in dark theme), radial lighting, top specular ridge and inner bevel once into the cached bitmap. |
| 174 brand-coloured logos + procedural fallback plates | Both catalog tiles and `PaneProgramTile` fallback tiles share `SkeuomorphicCardChrome` in the 61×48 artboard. |
| Redraw happens every frame while a tile animates | Unrotated tiles replay cached bitmaps (`lightTileBitmaps` / `darkTileBitmaps` LRUs); `SkeuomorphicCardChrome` pre-allocates all `Paint` and `Shader` instances. |
| Artwork carries its own squircle edge inset | Never clip the outer plate to a rounded rectangle; the 61×48 artboard reserves ~1.23 units of outer rim/shadow margin around the 58.54×45.54 plate. |

---

## 2. Unified skeuomorphic stack (all sizes)

Every program card tile (both catalog tiles in `ProgramVectorPicture.drawTile` and
fallback plates in `PaneProgramTile`) renders the following layers in order on
the 61×48 artboard:

1. **Outer contact shadow**: `0.98u` outset squircle (`rgba(0, 0, 0, 0.40)` in dark theme, `rgba(0, 0, 0, 0.19)` in light theme) around the plate.
2. **Outer rim margin**: `0.58u` outset squircle framing the card edge — crisp white (`#FFFFFF`) in light theme and soft charcoal (`#2A2A2E`) in dark theme, matching desktop Rex.
3. **Brand surface**:
   - Catalog tiles: compiled `surface` layer (warm-top → brand accent → HSV-shaded bottom ramp, bottom-quarter keel shadow, and top sheen).
   - Fallback tiles: vertical gradient from the skin or neutral palette.
   - Live progress countdown tiles (`progress.percent != null || progress.busy`): override any program artwork or fallback skin with a black skeuomorphic plate (`#242220` → `#000000`) and a white `CountdownIcon` dial.
4. **Radial surface lighting**: top-centered `RadialGradient` (`#FFFFFF` at `0.216` alpha fading to `0` at `0.58`, then `#000000` up to `0.072` alpha at bottom corners).
5. **Top specular highlight ridge**: `1u`-tall horizontal `LinearGradient` along the top inner edge (`#FFFFFF` peaking at `0.36` alpha at center, `0` at left/right).
6. **Inner bevel ring & device-pixel rim**: `1u` inset bevel stroke (`rgba(255, 255, 255, 0.14)` on dark/colored plates, `rgba(0, 0, 0, 0.085)` on bright light plates) plus a `1px` device-calibrated top/side rim highlight.
7. **Foreground symbol**: clipped to the plate outline, with a `1px` top bright offset behind catalog symbols.

---

## 3. Layer sources

| Layer | Producer | Notes |
| --- | --- | --- |
| `flat` / `surface` / `symbol` | `scripts/build_program_vectors.py` | Compiled into `program_tiles.json` with layer split offsets (`flat < surface < symbol`). |
| Outer shadow & theme-matched rim | `SkeuomorphicCardChrome.drawBackdrop` | Drawn with `FILL_AND_STROKE` parallel offsets (`1.96u` shadow and `1.16u` rim: `#FFFFFF` light / `#2A2A2E` dark) of the shared `plate` squircle. |
| Radial lighting, specular ridge, bevel & rim | `SkeuomorphicCardChrome.drawSurfaceOverlays` | Clipped to `plate`; shared between `ProgramVectorPicture` and `ProgramStack` fallback cards. |
| Bitmap cache | `ProgramVectorPicture.darkTileBitmaps` / `lightTileBitmaps` | 4-entry per-theme LRU keyed by exact floating-point pixel dimensions (`rotation == 0f`). |

---

## 4. Non-goals

- No single brand colour for all tiles.
- No per-frame shader or bitmap rebuild during animation; only `rainbowMarquee` animates over the cached card.
- No drop shadow on glyph-only tiles (`ProgramIcon`); they have no card plate.

---

## 5. Verification

1. `python3 rexling-android/scripts/build_program_vectors.py --check` — catalog and sources agree, and every tile declares `flat < surface < symbol`.
2. `ProgramCatalogDataTest` — layer offsets and plate paint are present.
3. Device instrumented checks: `ProgramTileRenderingTest` (including `skeuomorphicTileStackDrawsOuterRimShadowAndSpecularAtAllSizes`, `nativeVectorsKeepTheCatalogGeometryColorsAndLogoMasks`, and `sizeCachedTileReplayIsDeterministic`) and `UiAnimationPerformanceTest`.
