# Program Tile Material Design

Design and implementation notes for the program tiles used in the tab bar, pane
captions, floating dock and session header. The catalog contains 162 compiled
tile artworks; unknown programs fall back to a procedural plate.

Scope: two material tiers, one light and one dark chrome, and the compact sizes
used by the chrome.

---

## 1. Constraints

| Constraint | Consequence |
| --- | --- |
| Tiles render at roughly 18–27dp and 34dp | Compact tiles cannot carry a multi-layer material. |
| Vector catalog supports paths, solid fills and linear/radial gradients only | No runtime SVG filters; blur is pre-rendered or absent. |
| Hardware canvas has no blur filter below API 31 | Contact shadows are cached software-rendered bitmaps. |
| 162 brand-coloured logos | Material is derived from each tile's own plate colour. |
| Redraw happens every frame while a tile animates | Material is static per tile; no per-frame shader or bitmap rebuilds. |
| Artwork carries its own squircle edge inset | Never clip the plate to a rounded rectangle; use the artwork outline. |

---

## 2. Two tiers

The tile is one object, but it is not rendered with the same stack at every
size. The cues that make a 34dp tile read as ceramic collapse into subpixel
haze at 18dp.

### Compact: below 30dp

1. Contact shadow, sized for the tile.
2. Flat brand plate.
3. One device-pixel rim along the top and sides, fading by mid height.

No body ramp, no keel, no sheen, no symbol shadow or rim. The plate stays close
to the brand colour, and the rim is the only material cue.

### Detailed: 30dp and above

1. Contact shadow.
2. Body ramp: a warm top, the brand colour at mid height, and a darker bottom
   derived from the same hue. The ramp does not mix every accent toward a
   shared blue.
3. Keel: a soft dark gradient in the bottom quarter.
4. Rim: one device-pixel inner edge, brightest at the top.
5. Sheen: a low white wash over the top 40%.
6. Symbol: brand colours stay; the logo keeps a one-pixel bright rim offset above
   without downward hard shadow replays.
7. Grain: reserved for a later pass; not implemented.

Legibility rule: the compact tier is the default. The detailed tier is only
used when the tile is at least 30dp wide.

---

## 3. Directions considered

The original exploration compared three material languages:

| | A · frosted ceramic (shipped basis) | B · soft glass | C · embossed paper |
| --- | --- | --- | --- |
| Read | Matte disc, machined edge | Glossy domed lens | Stamped paper label |
| Body ramp | ±14% lightness, hue preserved | ±25%, wide hue travel | ±6%, nearly flat |
| Rim light | 1 device pixel | 1.4u | 0.9u, top only |
| Keel | 0.22α, bottom quarter | 0.30α | 0.14α |
| Sheen | 0.10α flat wash | 0.22α + specular band | none |
| Risk | Rim must be pixel-calibrated | Plastic, dated; fights flat chrome | Reads as a button |

A is the basis for the detailed tier. B is no longer used by the procedural
fallback: it had a 34% white wash and a 1dp white border, which made terminal
tiles look like a different product from the catalog tiles.

---

## 4. Layer sources

| Layer | Tier | Produced by | Notes |
| --- | --- | --- | --- |
| Flat plate | Compact | `scripts/build_program_vectors.py` | One path filled with the accent. |
| Body ramp | Detailed | `scripts/build_program_vectors.py` | Three stops: warm top, accent, HSV-shaded bottom. |
| Keel | Detailed | `scripts/build_program_vectors.py` | Bottom-quarter gradient in the accent's own dark tone. |
| Sheen | Detailed | `scripts/build_program_vectors.py` | Top-40% white wash. |
| Rim | Both | `ProgramVectorPicture.drawRim` | A 2px stroke clipped to the inner half, so one device pixel remains visible at every density. |
| Contact shadow | Both | `TileShadow` | Cached bitmap per tile and pixel size; blur and offset are tiered. |
| Symbol shadow | Detailed | — | Removed; previously unblurred downward replays produced a 2010s hard bevel / reflection. |
| Symbol rim | Detailed | `ProgramVectorPicture` | One device-pixel white offset above the logo. |
| Grain | Detailed | — | Not implemented. |

The build emits one command stream per tile with three offsets:

```json
{"flat": 1, "surface": 2, "symbol": 7}
```

`ProgramVectorPicture` records the flat plate, the ceramic surface and the
symbol as separate pictures, so switching tiers costs no per-frame work.
Unrotated tiles are then rasterized once per exact floating-point size. A
four-entry LRU per artwork replays those bitmaps during animation instead of
rebuilding paths, color-filter layers and the shadow matrix every frame.
Evicted bitmaps are not explicitly recycled because an older display list may
still reference them.

Only the header's front pane and the selected tab animate their marquee.
Background panes and inactive tabs retain the same treatment at a fixed phase;
this keeps the working cue without paying for every visible shader on every
frame.

---

## 5. Size table

| | Compact (<30dp) | Detailed (>=30dp) |
| --- | --- | --- |
| Plate | Flat accent | Body ramp + keel + sheen |
| Rim | 1 device px | 1 device px |
| Contact shadow dy / blur | 0.5 / 0.8dp at 22dp; 0.8 / 1.2dp up to 29dp | 1.0 / 1.6dp |
| Symbol shadow / rim | off | on |
| Grain | off | not implemented |

The rim is deliberately expressed in device pixels, not artwork units. A 1u
stroke on a 61-unit artboard becomes 0.3–0.8px at the sizes used by the chrome
and never reads as an edge.

---

## 6. Procedural fallback

Unknown programs keep the same plate language as the catalog:

- a solid or three-stop vertical gradient derived from the skin colours;
- no white top wash and no 1dp white border;
- a one-pixel inner rim with the same top-to-bottom falloff;
- a single Compose shadow, not a second shadow stacked on the material shadow.

`TERMINAL`, `SSH` and `TMUX` skins intentionally have no tile, so executable
and candidate artwork can still win. Their fallback plate is now flat rather
than glossy.

---

## 7. Non-goals

- No single brand colour for all tiles.
- No full-gradient fill over the logo.
- No clipping of the artwork silhouette; the plate edge belongs to the artwork.
- No per-frame blur, no per-frame `BitmapShader` rebuild, no animation inside
  the material.
- No drop shadow on glyph-only tiles; they have no plate.

---

## 8. Verification

1. `python3 scripts/build_program_vectors.py --check` — catalog and sources agree,
   and every tile declares `flat < surface < symbol`.
2. `ProgramCatalogDataTest` — layer offsets and plate paint are present.
3. Instrumented check on device: `ProgramTileRenderingTest`, then a screenshot
   of the tab bar in both themes with a long session title and three busy tabs.
4. Frame budget: `UiAnimationPerformanceTest` with a real Activity/Choreographer
   clock, one busy header and four busy tabs. Compare UI and RenderThread
   sections, not only frame callbacks. `sizeCachedTileReplayIsDeterministic`
   covers cache replay; the 2026-11-20 fixture comparison found zero differing
   pixels across eight size/program combinations. See
   `rexling-android/validation/ui-performance-2026-11-20.json`.

The previous `.preview/preview.html` reference was not checked in. Regenerate an
18/27/34dp ladder before changing material constants again.
