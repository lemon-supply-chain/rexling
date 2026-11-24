# Rex Beta Icon Skeuomorphic & Material Analysis

Inspection of the running `/Applications/Rex Beta.app` (PID `90659`, macOS build) shows two distinct icon rendering systems: the macOS application icon (`App Icon`) and the terminal program/block card tiles (`SuperBlockIcons`).

## 1. Application Icon (`App Icon` in `Assets.car`)

- **Source**: `/Applications/Rex Beta.app/Contents/Resources/Assets.car` (compiled with Xcode 27.1 CoreThemeDefinition / macOS `IconImageStack`).
- **Mechanism**: Uses macOS Tahoe / Icon Composer layered `IconImageStack` + `IconGroup` assets (1024×1024 Display P3) rather than a single flat bitmap, allowing CoreUI to compute real-time specular lighting, glass refraction, translucency blur, and layered drop shadows across Light (`NSAppearanceNameAqua`), Dark (`NSAppearanceNameDarkAqua`), and Tintable (`ISAppearanceTintable`) appearances:
  1. **Background layer**: `App Icon_Assets/system-light` / `system-dark` grayscale gradient.
  2. **Base body group (`App Icon/Group`)**: `1024×1024` `App Icon_Assets/Gradient` raster plate with `LayerHasSpecular = true`, `LayerGathersSpecularByElement = true`, `LayerShadowOpacity = 0.5`, `LayerShadowStyle = 3`.
  3. **Rexie silhouette group (`App Icon/Rexie 2`)**: `App Icon_Assets/Rexie Neue Color` (`image.svg`, `941×960`) tinted with `App Icon_Assets/Gradient-5`, `LayerHasLightingEffects = true`, `LayerBlurStrength = 0.2`, `LayerTranslucency = 0.5`, `LayerRefractionHeight = 0.0562`, `LayerRefractionStrength = 0.4225`, `LayerHasSpecular = true`, `LayerSpecularPlacement = 2`, `LayerShadowOpacity = 1`, `LayerShadowStyle = 2`.
  4. **Foreground facial glass group (`App Icon/Rexie`)**: Vector layers `App Icon_Assets/Eye` (`Gradient-3`) and `App Icon_Assets/Mouth Neue` (`Gradient-4`) with stronger glass parameters: `LayerBlurStrength = 0.5`, `LayerTranslucency = 0.4`, `LayerRefractionHeight = 0.4745`, `LayerRefractionStrength = -0.2571`, `LayerSpecularPlacement = 1`, `LayerShadowOpacity = 1`.

## 2. Program / Block Card Tiles (`SuperBlockIcons`)

- **Assets**: `/Applications/Rex Beta.app/Contents/Resources/SuperBlockIcons_SuperBlockIcons.bundle/Contents/Resources/Assets.car` stores 796 monochrome glyph/silhouette entries (`block-glyph-*`, `block-silhouette-*`), not pre-baked 3D cards.
- **Runtime synthesis**: `/Applications/Rex Beta.app/Contents/MacOS/Rex` (`SuperBlockIcons` module: `BlockGroupIconView`, `CardArtwork`, `BlockIconDefinition`, `BlockIconGradient`, `BlockGroupIconConfiguration`) composites the tactile 3D card surface at runtime and caches supersampled results (`SupersampledArtworkCacheKey`, `CardArtworkCacheKey`).
- **Observed configuration keys** (`BlockGroupIconConfiguration.json` / binary strings) and matching SVG card geometry in `rexling-android/artwork/program-icons/rexling/card-template.svg`:
  1. **Squircle geometry**: Continuous-curvature Bezier squircle controlled by `cornerRadiusRatio` (`73×60` viewBox, inset plate `58.54×45.54`).
  2. **Dual outer shadows**: Drop shadow (`outerShadowColorHex`, `outerShadowOpacity`, `outerShadowRadius`, `outerShadowOffsetY`) plus tight contact shadow (`outerShadowContactRadiusScale`, `outerShadowContactOpacityScale`; `rgba(0,0,0,0.4)` plate shadow in `card-template.svg`).
  3. **Outer rim & beveled inner stroke**: Solid outer rim (`outerRimColorHex`) and an inner bevel ring (`innerBorderWidth`, `innerBorderWidthMode`, `innerStrokeColorHex`, `innerStrokeOpacity`, `innerStrokeBlendMode`; `evenodd` ring path with `rgba(255,255,255,0.1)` in `card-template.svg`).
  4. **Top specular ridge**: 1px-tall horizontal specular highlight along the top inner edge (`highlightColorHex`, `highlightOpacity`, `highlightBlendMode`, `highlightOffsetY`; `linearGradient` peaking at `0.36` white opacity at center in `card-template.svg`).
  5. **Surface lighting & gradients**: Multi-stop `BlockIconGradient` (linear, `radial`, and `mesh` gradients with Display P3 support) blended via `gradientStrength` / `gradientBlendMode` (e.g. `softLight`) and `innerShadowColorHex` / `innerShadowOpacity` / `innerShadowBlendMode` (top `#FFFFFF` `0.216` to bottom `#000000` `0.072` radial ramp in `card-template.svg`).
  6. **Foreground glyph & stacked card motion**: Monochrome `block-glyph-*` tinted by `foregroundHex` / `foregroundGradient`, with multi-pane stack tilt (`cardRotationAmountDegrees`, `glyphOffsetAmount`) and spring attention motion (`attentionSpringAmount`, `attentionDampingAmount`, `attentionJumpAmount`, `attentionScaleAmount`).
