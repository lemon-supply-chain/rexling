# Program Tile Material Audit

Date: 2026-11-18
Scope: `docs/icon-material.md` versus the shipped catalog tiles (162) and the
procedural fallback path, at 18/27/34dp on light and dark chrome.

## Verdict

The direction is sound; the execution is not. At chrome sizes the tiles read as
a flat logo with a gradient wash and a smudged shadow, not as the matte ceramic
disc described in the design document. The gap comes from scale calibration and
from two material languages running side by side, not from skeuomorphism itself.

## Findings

1. **Two material languages coexist.** Catalog tiles use the new ceramic path
   (`ProgramStack.kt:72-79`), while `TERMINAL`, `SSH` and `TMUX` skins have no
   tile and fall back to the rejected soft-glass recipe: a 34% white top wash,
   a 1dp pure-white border and a 3dp shadow (`ProgramStack.kt:86-98`). Terminal
   is the most common pane identity, so the icon row mixes both languages.

2. **The rim is subpixel.** The build emits a 1.0u stroke and then clips its
   outer half (`build_program_vectors.py:101-110`). At 3x this is about 0.54px
   at 18dp and 0.84px at 34dp of visible edge. The symbol rim is offset by a
   fixed 0.45 device px (`ProgramVectorPicture.kt:74,99`), which cannot render
   as a line at any density. The "chromed top edge" is therefore a faint halo,
   not the cue that makes the plate read as an object.

3. **Shadows are calibrated for 34dp only.** `1.5 * density` blur and
   `0.9dp` offset are used for every tile size
   (`ProgramVectorPicture.kt:88-90`). At 18dp that is roughly 8% of the tile
   height; the design table asks for 0.8dp / 0.5dp. In a dense tab bar the
   halos merge into a dirty band.

4. **The symbol shadow is a hard double copy.** Two color-filtered replays at
   1.2u and 1.9u, with no blur (`ProgramVectorPicture.kt:72-73,98`). At 18/27dp
   this thickens strokes and reads as a 2010s bevel; the soft shadow in the
   design table was never implemented.

5. **The body ramp cools every accent toward the same blue.** `bottom` mixes
   with `#123A66` (`build_program_vectors.py:84`). 35 of 162 plates are light
   (L >= 0.82), including codex, cursor, git, github and copilot. White plates
   gain a gray-blue bottom; cream plates lose warmth (`git` #F0EFE7 becomes
   about #C3C6C3). The promised "top warmed" half is absent: the top is mixed
   with white, which desaturates rather than warms.

6. **Grain is documented but missing.** No runtime grain code exists; the
   material is therefore smooth plastic at 34dp, where grain was supposed to
   add physicality.

7. **Decorations do not follow the plate silhouette.** The marquee, highlight
   border and floating shadow use 5/7dp rounded rectangles
   (`ProgramStack.kt:32-34,75`), while the plate is a tighter squircle. Floating
   tiles also stack a 4dp Compose shadow on top of the intrinsic contact shadow,
   producing two different falloff curves.

8. **Likely layer-clipping bug.** `offsetSymbol` passes pixel `width/height`
   to `saveLayer` while the canvas is already in 61x48 artwork units
   (`ProgramVectorPicture.kt:81-85`). At densities below 3x the shadow and rim
   layers are clipped; the bounds should be `this.width/this.height`.

9. **Draw cost.** Three `saveLayer` calls per catalog tile per draw, and the
   `TileShadow` cache is keyed by every pixel size with no eviction. A long
   session that shows many tiles at several sizes accumulates tens of MB of
   shadow bitmaps.

## Recommendation

Calibrate the material in device pixels and tier it by size:

- 18/27dp: flat plate, 1 physical-pixel inner rim, size-scaled contact shadow.
  No sheen, no symbol shadow, no symbol rim.
- 34dp: add the body ramp, sheen and a soft pre-rendered symbol shadow; keep
  grain optional.
- Unify the procedural terminal/SSH/TMUX path with the same material, or make
  every tile glyph-only and flat. Do not keep both.
- Replace the fixed blue bottom mix with a hue-preserving darkening, and add a
  neutral keyline for light plates on light chrome.

The design document's own verification step (`.preview/preview.html`, the
18/27/34dp ladder diff) was never checked in, so the size regressions above
were not caught before the material shipped.

## Resolution

A first revision was implemented on the same date:

- Compact tiles now use a flat accent plate, a one-device-pixel inner rim and
  a size-scaled contact shadow. The body ramp, keel, sheen and symbol emboss
  are reserved for tiles at least 30dp wide.
- The vector build emits `flat`, `surface` and `symbol` layer offsets instead
  of a single plate/symbol split. The flat plate is the original accent, not a
  gradient.
- The rim is drawn at runtime with a 2px stroke clipped to the inner half, so
  one physical pixel remains visible at 1x, 2x and 3x.
- The body ramp no longer mixes every accent toward `#123A66`; the bottom is
  an HSV value scale of the accent, and the top is a warm lift.
- The procedural terminal/SSH/TMUX fallback no longer uses the 34% white wash
  or the 1dp white border. It uses a flat gradient plate and the same hairline.
- The floating double shadow and the 1dp highlight ring were removed; the
  highlight is now one device pixel.
- `saveLayer` bounds now use the 61x48 artwork space instead of pixel sizes.

Not implemented in this revision: the grain layer, a blurred symbol shadow,
and an eviction policy for the tile and shadow caches.
