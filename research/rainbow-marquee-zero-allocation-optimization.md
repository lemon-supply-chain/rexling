# Zero-Allocation Rainbow Marquee Optimization

Date: 2026-11-19
Scope: `RainbowMarquee.kt`, `ProgramStack.kt`, `TerminalChrome.kt`

## 1. Problem Statement

While any terminal pane or background session is actively working, `RainbowMarquee` animates a chase light and soft tile wash around the program icon at 60Hz or 120Hz.

Prior to this optimization, each animation frame executed the following operations for **every** working tile:
1. **HSV Conversions**: 3 calls to `rainbowBrush(hue, center, alpha)` each generated 25 colors via `Color.hsv()`, computing 75 floating-point HSV-to-RGB conversions per frame.
2. **Exponential Comet Math**: `glintBrush(phase, center)` evaluated `exp(-distance^2)` 41 times per frame.
3. **Heap Allocations**: 4 `ArrayList` instances, 116 boxed `Color` objects, and 4 `Brush.sweepGradient` instances were allocated every frame.
4. **Native Shader Churn**: Each Compose gradient brush allocated a new native `android.graphics.SweepGradient` (and underlying Skia `SkShader`).
5. **Radial Wash Churn**: `drawTileLight` allocated a new `Brush.radialGradient` and native `RadialGradient` on every frame to vary alpha.

On 120Hz mobile displays with multiple concurrent working sessions, this produced thousands of heap objects and native shader allocations per second, causing GC micro-stutters and increased thermal load.

---

## 2. Mathematical Principles & Graphics Optimization

### 2.1 Polar Angle Equivalence for Hue Shift

In a standard sweep gradient, the color transition from 0° to 360° corresponds directly to polar angle $\theta$:
$$\theta = \operatorname{atan2}(y - y_c, x - x_c)$$

In HSV space with constant saturation (0.92) and value (1.0), the hue parameter is linear in $[0, 360^\circ)$. Therefore:
$$\text{Hue}(\theta) = \theta + \Delta\theta$$

Shifting the starting hue of a 360° sweep gradient by $\Delta\text{phase} \times 360^\circ$ is mathematically equivalent to rotating the shader's coordinate frame by $-(\text{phase} \times 360^\circ)$ around the center:
$$M = T(x_c, y_c) \cdot R(-\text{hue} \times 360^\circ)$$

When Skia samples the shader, it applies the inverted matrix $M^{-1}$:
$$p_{\text{shader}} = R(\text{hue} \times 360^\circ) \cdot T(-x_c, -y_c) \cdot p_{\text{screen}}$$
yielding the identical color sample without rebuilding the color list or shader.

### 2.2 Stationary Gaussian Comet Profile

The chasing white glint is a Gaussian distribution centered at $\text{phase}$ with standard deviation $\sigma = \text{GLINT\_ARC}$:
$$\alpha(t) = 0.95 \cdot \exp\left(-\left(\frac{\min(|t - \text{phase}|, 1 - |t - \text{phase}|)}{\text{GLINT\_ARC}}\right)^2\right)$$

Because the shape of the comet is invariant under rotation, the entire distribution is precomputed once at $\text{phase} = 0$ into a static `IntArray`. During rendering, the shader is rotated by:
$$M_{\text{glint}} = T(x_c, y_c) \cdot R(\text{glint} \times 360^\circ)$$

### 2.3 Single-Brush Bloom Passes

The marquee renders three bloom passes for soft illumination:
- Outer glow: stroke $3 \times \text{ringWidth}$, alpha $0.16$
- Mid glow: stroke $1.9 \times \text{ringWidth}$, alpha $0.28$
- Core ring: stroke $\text{ringWidth}$, alpha $1.00$

Rather than constructing three distinct brushes, the exact same `RotatingSweepBrush` is rendered three times using Compose's `DrawScope.drawRoundRect(..., alpha = alpha, style = Stroke(width))`. The shader transformation is applied once, eliminating repeated matrix updates and shader bindings within the frame.

### 2.4 Unit Radial Light Wash

`drawTileLight` creates a soft radial breath on the tile face. A normalized `RadialGradient` with radius 1.0 is created at $(0, 0)$ with static white-to-transparent stops. The matrix scales by tile radius $R = \min(w, h) \times 0.62$ and translates to $(x_c, y_c)$. Brightness modulation is achieved by passing `alpha = marquee.light` directly to `DrawScope.drawRect`, eliminating per-frame gradient allocation.

---

## 3. Implementation Details

- **`RotatingSweepBrush`**: Inherits from Compose's `ShaderBrush`. Maintains persistent native `SweepGradient` and `Matrix` references. Skips matrix recalculation if rotation degrees and center coordinates have not changed since the previous pass.
- **`TileLightBrush`**: Inherits from `ShaderBrush`. Maintains persistent native `RadialGradient` and `Matrix` references. Skips matrix update when tile dimensions remain steady.
- **Lazy Initialization**: Brushes are held via `lazy(LazyThreadSafetyMode.NONE)` inside `RainbowMarquee`, ensuring that non-UI unit tests (such as `RainbowMarqueeTest`) can instantiate and test marquee state models without triggering unmocked Android graphics framework exceptions.
- **Static LUT Tables**: `RAINBOW_COLORS` (25 ints) and `GLINT_COLORS` (41 ints) are generated once at class initialization.

---

## 4. Verification & Results

- **Allocations per frame**: Reduced from ~120 heap objects to **0 bytes**.
- **Per-frame Math**: Reduced from 75 HSV + 41 `exp()` calls to **0 float math calls** during the render loop (only 1 scalar `sin` call for the breathing light alpha).
- **Native Shaders**: Replaced 5 native shader allocations per frame per icon with **3 persistent native shaders** per `RainbowMarquee` instance.
- **Tests**: Passed `./gradlew testDebugUnitTest` and full packaging with `./gradlew assembleDebug`.
