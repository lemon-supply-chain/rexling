# Program Tile Material Design

Design direction and implementation plan for the skeuomorphic program tiles used in
the tab bar, pane captions, floating dock and session header. This document replaces
the earlier attempt that applied the generic frosted-glass SVG recipe directly.

Scope: 162 compiled tile artworks, two rendering paths (baked vector artwork and
procedural skin), three real display sizes, one light and one dark chrome.

---

## 1. Constraints that shape the design

| Constraint | Consequence |
| --- | --- |
| Tiles render at 18dp, 27dp and 34dp only | Detail below ~0.5dp is invisible. No layered micro-texture, no multi-stop gloss bands. |
| Vector catalog supports paths, solid fills and linear/radial gradients only | No `feGaussianBlur`, `feTurbulence`, `feComposite` in the artwork stream. Blur must be pre-rendered or faked. |
| Hardware canvas has no blur filter below API 31 | Shadows are cached software-rendered bitmaps, not per-frame effects. |
| 162 brand-coloured logos | A single primary colour would destroy identity. Material must be derived from each tile's own plate colour. |
| Redraw happens every frame while a tile animates | Material must be static per tile: zero extra allocations, no `saveLayer` unless it replaces a cheaper path. |
| Artwork carries its own rounded edge inset ~10% | Never clip the artwork; never paint over its silhouette edge. |

---

## 2. Material language

One language, three intensities. Physically it reads as a **matte ceramic disc with a
chromed top edge**, lit from directly above.

Order from back to front:

1. **Contact shadow** — the tile sits on the surface. Blurred, tinted with the plate
   colour darkened to 20% value, never pure black.
2. **Body** — vertical ramp: top lightened and warmed, middle at brand colour, bottom
   darkened and cooled. Hue moves a few degrees, not just lightness, so the ramp looks
   like a lit curved surface instead of a gradient fill.
3. **Keel** — inner shadow along the bottom third, cool dark, tight. Sells the pressed
   edge without a second visible outline.
4. **Rim light** — a 1px bright edge along the top and sides, fading out by mid height.
   This line is what makes a flat plate read as a physical object.
5. **Sheen** — very low opacity white wash over the top 40% (direction A) or a defined
   specular band (direction B). Off for the matte paper direction.
6. **Symbol** — brand colours stay. Instead of recolouring, it gains a tight contact
   shadow below and a thin bright edge on its upper contour.
7. **Grain** — 1 texel per device pixel noise, 6–10% alpha, only at 34dp and above.
   Below that it aliases into mush and costs sharpness.

Legibility rule: at 18dp only 1, 3 and 4 survive; 5 drops to a whisper; 7 is disabled.

---

## 3. Directions

| | A · frosted ceramic (recommended) | B · soft glass | C · embossed paper |
| --- | --- | --- | --- |
| Read | Matte disc, machined edge | Glossy domed lens | Stamped paper label |
| Body ramp | ±14% lightness, ±6° hue | ±25%, wider hue travel | ±6%, nearly flat |
| Rim light | 0.45α, 1.1u | 0.58α, 1.4u | 0.30α, 0.9u, top only |
| Keel | 0.22α, bottom 26% | 0.30α, bottom 30% | 0.14α, bottom 22% |
| Sheen | 0.10α flat wash | 0.22α + specular band | none |
| Contact shadow | dy 1.4, blur 2.2, 0.34α | dy 1.8, blur 3.0, 0.44α | dy 0.8, blur 1.6, 0.22α |
| Symbol shadow | dy 1.6, blur 1.6, 0.42α | dy 2.0, blur 2.2, 0.52α | dy 1.0, blur 1.0, 0.28α |
| Grain | 6% at ≥34dp | 5% at ≥34dp | 3% at ≥34dp |
| Risk | Needs careful rim at 18dp | Plastic, dated; fights the flat app chrome | Reads as a button, weak on dark chrome |

A is recommended: it keeps the existing flat app language, survives 18dp, and the
brand colour still owns the tile.

Reference render: `.preview/preview.html` (generated from the real compiled logo paths,
same plate geometry, three sizes on light and dark chrome).

---

## 4. Where each layer comes from

Surface layers are **baked at build time** because they are pure gradient/path work
and the rasteriser already supports them. Anything requiring blur or silhouette
extraction happens at **runtime**.

| Layer | Produced by | Notes |
| --- | --- | --- |
| Body ramp | `scripts/build_program_vectors.py` | Replace the artwork's own plate fill (first path inside the plate clip) with a 3-stop ramp derived from `accents[name]`. |
| Keel | compiler | Extra `path(plate)` fill with a bottom-up gradient, emitted right after the plate group. |
| Rim light | compiler | `path(plate)` stroke with a top-to-bottom fading gradient; no geometry needed beyond the plate path already used for the clip. |
| Sheen | compiler | One extra gradient rect, plate-clipped. |
| Contact shadow | runtime, cached | Rendered once per (tile, size) into a small bitmap with a software canvas and `BlurMaskFilter`, then drawn as a bitmap. Works on API 28–37 identically. |
| Symbol shadow | runtime | The symbol half of the stream is already split out; replay it into a `saveLayer` with a `SRC_IN` colour filter at the dark accent tone. |
| Symbol rim light | runtime | Draw the symbol picture four times at ±0.5px in pure white inside a layer, masked by the symbol's own alpha. |
| Grain | runtime, cached | 64×64 deterministic speckle bitmap, `BitmapShader` repeat, 1 texel per device pixel. |

The compiler already knows the plate path (it is the first `clip` in the stream) and
the plate colour (emitted as `accents`), so layers 1–4 need no per-icon geometry work.

---

## 5. Implementation steps

1. `build_program_vectors.py`: emit `plate` (3 gradient stops per tile) and `extras`
   (keel, rim, sheen commands) alongside the existing `symbols`/`accents`.
   Regenerate `program_tiles.json`.
2. `ProgramVectorPicture`: split the replayed picture into three parts —
   `surface` (body + keel + rim + sheen), `symbol`, and drop the baked shadow paths
   that the new contact shadow replaces.
3. Add `TileShadow` cache: keyed by tile name + pixel size, holding the blurred plate
   silhouette bitmap plus its offset.
4. `drawTile()` becomes: shadow bitmap, surface, symbol shadow, symbol, symbol rim.
5. Delete the current sheen/blur approximations that conflict with the new layers.
6. Keep the busy marquee ring and the busy light wash untouched; they are separate
   signals layered on top of the tile.

---

## 6. Size table

Material constants are expressed in tile units (1u = tile height / 48) and clamped to
device pixels, so 18dp and 34dp look like the same object at different scales.

| | 18dp | 27dp | 34dp |
| --- | --- | --- | --- |
| Rim | 1.1u (0.41dp) | 1.1u (0.62dp) | 1.1u (0.78dp) |
| Keel height | 26% | 27% | 28% |
| Contact shadow dy / blur | 0.5dp / 0.8dp | 0.8dp / 1.2dp | 1.0dp / 1.6dp |
| Symbol shadow dy / blur | 0.22dp / 0.35dp | 0.35dp / 0.5dp | 0.45dp / 0.7dp |
| Sheen | 0.08α | 0.10α | 0.10α |
| Grain | off | off | 6% |

Every value is clamped with `max(0.5f, value)` in device pixels so a 1× density screen
still shows the rim.

---

## 7. Non-goals

- No single brand colour for all tiles.
- No full-gradient fill over the logo: the earlier white-to-accent fill destroyed
  multi-colour logos (`git`, `kube`, `claude`).
- No clipping of the artwork silhouette; the plate edge belongs to the artwork.
- No per-frame blur, no per-frame `BitmapShader` rebuild, no animation inside the
  material.
- No drop shadow on glyph-only tiles; they have no plate.

---

## 8. Verification

1. `python3 scripts/build_program_vectors.py --check` — catalog and sources agree.
2. Offline strip: render the tile ladder (18/27/34dp, light + dark chrome) to PNG with
   the preview generator, diff against the previous iteration before touching the app.
3. Instrumented check on device: `ProgramTileRenderingTest`, then a screenshot of the
   tab bar in both themes with a long session title and three busy tabs.
4. Frame budget: `TerminalPerformanceTest` with four busy tabs, compare jank counts
   against the current build; the material must be draw-phase only.