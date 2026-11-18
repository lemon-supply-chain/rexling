#!/usr/bin/env python3
"""Derive the secondary/tertiary accent triads each theme was missing.

Rexling themes ship one authored accent (`accentSolid` / `accentContainer`). A
single accent cannot carry three jobs (action, selection, working), so each theme
needs a quiet second accent and a related third. The authored accent stays
exactly as imported; the two derived accents keep its hue, drop chroma for the
selection role, and rotate the hue for the working role.

Derivation is done in OKLCH (Björn Ottosson), which keeps lightness perceptually
even across hues, so containers land at the same visual weight in every theme.
Run idempotently; the authored keys are the only inputs:

    python3 scripts/derive_theme_roles.py

`import_rex_themes.py` calls the same function when it projects a fresh catalog.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

DERIVED_KEYS = (
    "accentSecondary",
    "accentOnSecondary",
    "accentSecondaryContainer",
    "accentOnSecondaryContainer",
    "accentTertiary",
    "accentOnTertiary",
    "accentTertiaryContainer",
    "accentOnTertiaryContainer",
)

# Text-on-container pairs the app actually renders. The authored ink is kept when
# it already clears AA; otherwise it is nudged in lightness only, so hue and
# identity survive a 1-5% correction.
INK_PAIRS = (
    ("content", "surface"),
    ("contentSecondary", "surface"),
    ("contentSecondary", "surfaceRaised"),
    ("contentSecondary", "surfaceSubtle"),
    ("accentOnContainer", "accentContainer"),
    ("dangerOnContainer", "dangerContainer"),
)

AUTHORED_KEYS = (
    "canvas", "surface", "surfaceSubtle", "surfaceRaised", "strokeSubtle", "stroke",
    "content", "contentSecondary", "accentSolid", "accentOnSolid", "accentContainer",
    "accentOnContainer", "dangerSolid", "dangerOnSolid", "dangerContainer",
    "dangerOnContainer",
)

# OKLCH targets per appearance. L is perceptual lightness (0..1), C is chroma.
# Selecting is quieter than acting, and working is related but distinct.
TONES = {
    False: {  # light appearance
        "secondary": (0.50, 0.50, 0.08),
        "secondaryContainer": (0.92, 0.35, 0.045),
        "onSecondaryContainer": (0.35, 0.60, 0.10),
        "tertiary": (0.52, 0.75, 0.13),
        "tertiaryContainer": (0.92, 0.40, 0.05),
        "onTertiaryContainer": (0.35, 0.70, 0.11),
    },
    True: {  # dark appearance
        "secondary": (0.82, 0.50, 0.08),
        "secondaryContainer": (0.30, 0.35, 0.045),
        "onSecondaryContainer": (0.90, 0.60, 0.10),
        "tertiary": (0.85, 0.75, 0.13),
        "tertiaryContainer": (0.30, 0.40, 0.05),
        "onTertiaryContainer": (0.90, 0.70, 0.11),
    },
}

HUE_SHIFT_DEGREES = 60.0
# A rotated hue that lands near the theme's own danger hue would read as an
# error state, so those themes rotate the other way instead.
DANGER_HUE_GUARD_DEGREES = 45.0


def _linear(channel: float) -> float:
    return channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4


def _encode(channel: float) -> float:
    channel = max(0.0, min(1.0, channel))
    return 12.92 * channel if channel <= 0.0031308 else 1.055 * channel ** (1 / 2.4) - 0.055


def hex_to_rgb(value: str) -> tuple[float, float, float]:
    assert len(value) == 7 and value[0] == "#", value
    return tuple(int(value[i:i + 2], 16) / 255 for i in (1, 3, 5))


def rgb_to_hex(rgb: tuple[float, float, float]) -> str:
    return "#" + "".join(f"{round(255 * c):02x}" for c in rgb)


def rgb_to_oklch(value: str) -> tuple[float, float, float]:
    r, g, b = (_linear(c) for c in hex_to_rgb(value))
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_, m_, s_ = (c ** (1 / 3) if c >= 0 else -((-c) ** (1 / 3)) for c in (l, m, s))
    lightness = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
    a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
    bb = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
    return lightness, math.hypot(a, bb), math.degrees(math.atan2(bb, a)) % 360


def oklch_to_hex(lightness: float, chroma: float, hue_degrees: float) -> str:
    hue = math.radians(hue_degrees % 360)
    a, b = chroma * math.cos(hue), chroma * math.sin(hue)
    l_ = lightness + 0.3963377774 * a + 0.2158037573 * b
    m_ = lightness - 0.1055613458 * a - 0.0638541728 * b
    s_ = lightness - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    return rgb_to_hex((_encode(r), _encode(g), _encode(bl)))


def luminance(value: str) -> float:
    r, g, b = (_linear(c) for c in hex_to_rgb(value))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: str, b: str) -> float:
    high, low = max(luminance(a), luminance(b)) + 0.05, min(luminance(a), luminance(b)) + 0.05
    return high / low


def readable_on(background: str) -> str:
    """Black or white, whichever clears the text floor on this background."""
    return max(("#000000", "#ffffff"), key=lambda c: contrast(c, background))


def hue_distance(a: float, b: float) -> float:
    return abs((a - b + 180.0) % 360.0 - 180.0)


def working_hue(seed_hue: float, danger_hue: float) -> float:
    """Rotate the accent for the working role, away from the danger hue."""
    forward = (seed_hue + HUE_SHIFT_DEGREES) % 360.0
    backward = (seed_hue - HUE_SHIFT_DEGREES) % 360.0
    if hue_distance(forward, danger_hue) >= DANGER_HUE_GUARD_DEGREES:
        return forward
    return backward if hue_distance(backward, danger_hue) > hue_distance(forward, danger_hue) else forward


def _shift_lightness(value: str, delta: float) -> str:
    lightness, chroma, hue = rgb_to_oklch(value)
    return oklch_to_hex(max(0.0, min(1.0, lightness + delta)), chroma, hue)


def ensure_contrast(ink: str, background: str, floor: float = 4.5) -> str:
    """Nudge an ink in lightness until it clears the text floor on its background."""
    if contrast(ink, background) >= floor:
        return ink
    direction = 1.0 if luminance(background) < 0.18 else -1.0
    for step in range(1, 201):
        candidate = _shift_lightness(ink, direction * 0.005 * step)
        if contrast(candidate, background) >= floor:
            return candidate
    return "#ffffff" if direction > 0 else "#000000"


def ensure_readable(ui: dict[str, str]) -> dict[str, str]:
    """Apply [INK_PAIRS], returning the keys that moved."""
    moved: dict[str, str] = {}
    for ink_key, background_key in INK_PAIRS:
        adjusted = ensure_contrast(ui[ink_key], ui[background_key])
        if adjusted != ui[ink_key]:
            moved[ink_key] = f"{ui[ink_key]}->{adjusted} on {background_key} ({contrast(ui[ink_key], ui[background_key]):.2f})"
            ui[ink_key] = adjusted
    return moved


def derived_roles(ui: dict[str, str], dark: bool) -> dict[str, str]:
    seed_l, seed_c, seed_h = rgb_to_oklch(ui["accentSolid"])
    _, _, danger_h = rgb_to_oklch(ui["dangerSolid"])
    rotate_to = working_hue(seed_h, danger_h)
    tones = TONES[dark]
    out: dict[str, str] = {}
    for name, (lightness, chroma_ratio, chroma_cap) in tones.items():
        chroma = min(seed_c * chroma_ratio, chroma_cap)
        hue = rotate_to if "tertiary" in name.lower() else seed_h
        out[name] = oklch_to_hex(lightness, chroma, hue)
    secondary, tertiary = out["secondary"], out["tertiary"]
    out["accentSecondary"] = secondary
    out["accentOnSecondary"] = readable_on(secondary)
    out["accentSecondaryContainer"] = out.pop("secondaryContainer")
    out["accentOnSecondaryContainer"] = out.pop("onSecondaryContainer")
    out["accentTertiary"] = tertiary
    out["accentOnTertiary"] = readable_on(tertiary)
    out["accentTertiaryContainer"] = out.pop("tertiaryContainer")
    out["accentOnTertiaryContainer"] = out.pop("onTertiaryContainer")
    return {key: out[key] for key in DERIVED_KEYS}


def extend_theme(theme: dict, ui: dict[str, str] | None = None) -> dict[str, str]:
    colors = dict(ui if ui is not None else theme["ui"])
    colors.update(derived_roles(colors, bool(theme["dark"])))
    return colors


def audit(rows: list[tuple[str, bool, dict[str, str]]]) -> list[str]:
    problems: list[str] = []
    for theme_id, dark, ui in rows:
        checks = (
            ("onSelection on selection container", ui["accentOnSecondaryContainer"], ui["accentSecondaryContainer"], 4.5),
            ("onWorking on working container", ui["accentOnTertiaryContainer"], ui["accentTertiaryContainer"], 4.5),
            ("onSecondary on secondary", ui["accentOnSecondary"], ui["accentSecondary"], 4.5),
            ("onTertiary on tertiary", ui["accentOnTertiary"], ui["accentTertiary"], 4.5),
            ("working on surface", ui["accentTertiary"], ui["surface"], 3.0),
            ("action on surface", ui["accentSolid"], ui["surface"], 3.0),
        )
        for label, fg, bg, floor in checks:
            ratio = contrast(fg, bg)
            if ratio < floor:
                problems.append(f"{theme_id} {'dark' if dark else 'light'}: {label} {ratio:.2f} < {floor}")
    return problems


def main() -> int:
    target = Path(__file__).resolve().parents[1] / "app/src/main/assets/themes/rex-builtins.json"
    themes = json.loads(target.read_text())
    rows = []
    nudged = 0
    for theme in themes:
        ui = theme["ui"]
        missing = [key for key in AUTHORED_KEYS if key not in ui]
        if missing:
            raise SystemExit(f"{theme['id']}: missing authored keys {missing}")
        moved = ensure_readable(ui)
        if moved:
            nudged += 1
            print(f"  {theme['id']}: " + "; ".join(f"{k} {v}" for k, v in moved.items()))
        ui.update(derived_roles(ui, bool(theme["dark"])))
        assert all(ui[key].startswith("#") and len(ui[key]) == 7 for key in DERIVED_KEYS), theme["id"]
        rows.append((theme["id"], bool(theme["dark"]), ui))
    problems = audit(rows)
    target.write_text(json.dumps(themes, separators=(",", ":")) + "\n")
    print(f"{len(themes)} themes updated with {len(DERIVED_KEYS)} derived roles; {nudged} themes had an authored ink nudged for AA")
    if problems:
        print(f"{len(problems)} contrast notes (authored accents keep their hue; see each line):")
        for line in problems:
            print("  " + line)
    else:
        print("all derived pairs clear their contrast floors")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())