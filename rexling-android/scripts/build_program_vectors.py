#!/usr/bin/env python3
"""Compile the retained catalog SVG subset to native Canvas path commands.

No rasterization: SVG masks are monochrome alpha logos and can be drawn as tinted
paths. Unsupported SVG features fail generation instead of silently losing art.
"""
import base64
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork/program-icons/superlogical"
OVERLAY = ROOT / "artwork/program-icons/rexling"
OUTPUT = ROOT / "app/src/main/assets/program_tiles.json"
INDEX_OUTPUT = ROOT / "app/src/main/assets/program_index.json"


def tag(node):
    return node.tag.rsplit("}", 1)[-1]


def number(value):
    return float(str(value).removesuffix("px"))


def shapes(node):
    kind = tag(node)
    if kind == "path":
        return node.attrib["d"]
    if kind == "rect":
        x, y, w, h = [number(node.get(k, 0)) for k in ("x", "y", "width", "height")]
        rx = number(node.get("rx", node.get("ry", 0)))
        ry = number(node.get("ry", rx))
        if rx > 0 or ry > 0:
            rx = min(rx, w / 2)
            ry = min(ry, h / 2)
            return f"M{x+rx},{y}h{w-2*rx}a{rx},{ry} 0 0 1 {rx},{ry}v{h-2*ry}a{rx},{ry} 0 0 1 {-rx},{ry}h{-(w-2*rx)}a{rx},{ry} 0 0 1 {-rx},{-ry}v{-(h-2*ry)}a{rx},{ry} 0 0 1 {rx},{-ry}Z"
        return f"M{x},{y}h{w}v{h}h{-w}Z"
    if kind in ("polygon", "polyline"):
        points = re.findall(r"[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?", node.attrib["points"])
        assert len(points) % 2 == 0
        return "M" + "L".join(",".join(points[i:i+2]) for i in range(0, len(points), 2)) + ("Z" if kind == "polygon" else "")
    if kind == "circle":
        cx, cy, r = [number(node.get(k, 0)) for k in ("cx", "cy", "r")]
        return f"M{cx-r},{cy}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 {-2*r},0Z"
    if kind == "ellipse":
        cx, cy, rx, ry = [number(node.get(k, 0)) for k in ("cx", "cy", "rx", "ry")]
        return f"M{cx-rx},{cy}a{rx},{ry} 0 1,0 {2*rx},0a{rx},{ry} 0 1,0 {-2*rx},0Z"
    if kind == "line":
        x1, y1, x2, y2 = [number(node.get(k, 0)) for k in ("x1", "y1", "x2", "y2")]
        return f"M{x1},{y1}L{x2},{y2}"
    raise ValueError(f"Unsupported shape: {kind}")


def reference(value):
    assert value.startswith("url(#") and value.endswith(")"), value
    return value[5:-1]


def viewport(root):
    box = root.get("viewBox")
    if box:
        return tuple(map(float, box.split()))
    return 0.0, 0.0, number(root.get("width", 24)), number(root.get("height", 24))


def compile_icon(root, ink=None, crop=True):
    commands = [{"op": "translate", "x": -6, "y": -6}] if crop else []

    def draw(node, inherited, definitions, tint=None, opacity=1):
        ink_color = tint or ink
        kind = tag(node)
        assert node.get("filter") is None, "SVG filters are not supported"
        if kind in ("defs", "title", "desc"):
            return
        style = inherited.copy()
        if "style" in node.attrib:
            for item in node.attrib["style"].split(";"):
                if ":" in item:
                    k, v = item.split(":", 1)
                    k, v = k.strip(), v.strip()
                    if k in ("fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin"):
                        style[k] = v
        style.update({k: node.attrib[k] for k in ("fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin") if k in node.attrib})
        opacity *= float(node.get("opacity", 1))
        commands.append({"op": "save"})
        if node.get("transform"):
            commands.append({"op": "transform", "value": node.get("transform")})
        if node.get("clip-path"):
            clip = definitions[reference(node.get("clip-path"))]
            assert len(clip) == 1
            commands.append({"op": "clip", "path": shapes(clip[0])})
        if node.get("mask"):
            mask = definitions[reference(node.get("mask"))]
            image = next(x for x in mask if tag(x) == "image")
            href = image.get("href", "")
            assert href.startswith("data:image/svg+xml;base64,")
            logo = ET.fromstring(base64.b64decode(href.split(",", 1)[1]))
            color = ink_color or (node[0].attrib["fill"] if len(node) > 0 and "fill" in node[0].attrib else "#ffffff")
            x, y, w, h = [number(image.get(k)) for k in ("x", "y", "width", "height")]
            vx, vy, vw, vh = map(float, logo.attrib["viewBox"].split())
            scale = min(w/vw, h/vh)
            commands.extend([{"op": "translate", "x": x+(w-vw*scale)/2, "y": y+(h-vh*scale)/2},
                             {"op": "scale", "x": scale, "y": scale},
                             {"op": "translate", "x": -vx, "y": -vy}])
            draw(logo, {}, {x.get("id"): x for x in logo.iter() if x.get("id")}, color, opacity)
        elif kind == "image":
            href = node.get("href", node.get("{http://www.w3.org/1999/xlink}href", ""))
            assert href.startswith("data:image/svg+xml;base64,"), "Only embedded vector images are supported"
            logo = ET.fromstring(base64.b64decode(href.split(",", 1)[1]))
            x, y, w, h = [number(node.get(k, 0)) for k in ("x", "y", "width", "height")]
            vx, vy, vw, vh = map(float, logo.attrib["viewBox"].split())
            scale = min(w/vw, h/vh)
            commands.extend([{"op": "translate", "x": x+(w-vw*scale)/2, "y": y+(h-vh*scale)/2},
                             {"op": "scale", "x": scale, "y": scale},
                             {"op": "translate", "x": -vx, "y": -vy}])
            draw(logo, {}, {x.get("id"): x for x in logo.iter() if x.get("id")}, ink_color, opacity)
        elif kind in ("defs", "title", "desc", "mask"):
            return
        elif kind == "use":
            href = node.attrib.get("{http://www.w3.org/1999/xlink}href") or node.attrib.get("href") or node.attrib.get("xlink:href", "")
            if href.startswith("#") and href[1:] in definitions:
                draw(definitions[href[1:]], style, definitions, tint, opacity)
        elif kind in ("svg", "g"):
            for child in node:
                draw(child, style, definitions, tint, opacity)
        elif kind in ("path", "rect", "polygon", "polyline", "circle", "ellipse", "line"):
            fill = style.get("fill", "black")
            stroke = style.get("stroke")
            if fill != "none":
                paint = {"color": ink_color or fill, "alpha": opacity*float(style.get("fill-opacity", 1))}
                if fill.startswith("url("):
                    gradient = definitions[reference(fill)]
                    assert tag(gradient) in ("linearGradient", "radialGradient")
                    paint = {"gradient": tag(gradient), "attributes": gradient.attrib,
                             "stops": [dict(s.attrib, **{"stop-color": ink_color or s.get("stop-color", "black")}) for s in gradient],
                             "alpha": paint["alpha"]}
                commands.append({"op": "path", "path": shapes(node), "evenOdd": style.get("fill-rule") == "evenodd", "paint": paint})
            if stroke and stroke not in ("none", "transparent"):
                paint = {"color": ink_color or stroke, "alpha": opacity*float(style.get("stroke-opacity", 1)),
                         "stroke": True, "strokeWidth": float(style.get("stroke-width", 1)),
                         "strokeCap": style.get("stroke-linecap", "butt"), "strokeJoin": style.get("stroke-linejoin", "miter")}
                commands.append({"op": "path", "path": shapes(node), "evenOdd": False, "paint": paint})
        else:
            raise ValueError(f"Unsupported SVG element: {kind}")
        commands.append({"op": "restore"})

    draw(root, {}, {x.get("id"): x for x in root.iter() if x.get("id")})
    return commands


def compile_glyph(root):
    x, y, width, height = viewport(root)
    commands = compile_icon(root, ink="#FFFFFF", crop=False)
    if x or y:
        commands.insert(0, {"op": "translate", "x": -x, "y": -y})
    return {"width": width, "height": height, "commands": commands}


def program_kinds():
    text = (ROOT / "app/src/main/java/dev/shulhi/rex/ProgramIdentity.kt").read_text()
    body = re.search(r"enum class ProgramKind\s*\{([^}]*)\}", text).group(1)
    return {name.strip() for name in body.split(",") if name.strip()}


def check_sources():
    metadata = json.loads((SOURCE / "metadata.json").read_text())
    for key, folder in (("sha256", SOURCE), ("glyph_sha256", SOURCE / "glyphs")):
        for name, digest in metadata[key].items():
            path = folder / f"{name}.svg"
            assert path.exists(), f"Missing source: {path}"
            assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, f"Changed source: {path}"


def validate(manifest, icons, glyphs):
    assert manifest["version"] == 1
    assert set(manifest["skins"]) == program_kinds(), "Skins must cover every ProgramKind"
    for kind, skin in manifest["skins"].items():
        if skin.get("tile"):
            assert skin["tile"] in icons, f"{kind} tile is missing: {skin['tile']}"
        if skin.get("glyph"):
            assert skin["glyph"] in glyphs, f"{kind} glyph is missing: {skin['glyph']}"
        for name, tile in skin.get("variants", {}).items():
            assert tile in icons, f"{kind} variant {name} is missing: {tile}"
        colors = skin.get("colors", [])
        assert not colors or len(colors) == 3, f"{kind} needs three colors"
        assert all(re.fullmatch(r"#[0-9A-Fa-f]{6}", color) for color in colors), f"{kind} has an invalid color"
    for name, tile in manifest["aliases"].items():
        assert tile in icons, f"Alias {name} is missing: {tile}"


def build():
    check_sources()
    icons = {p.stem.replace("-", "_"): compile_icon(ET.parse(p).getroot()) for p in sorted(SOURCE.glob("*.svg"))}
    icons.update({p.stem.replace("-", "_"): compile_icon(ET.parse(p).getroot())
                  for p in sorted((OVERLAY / "tiles").glob("*.svg"))})
    glyphs = {p.stem.replace("-", "_"): compile_glyph(ET.parse(p).getroot())
              for folder in (SOURCE / "glyphs", OVERLAY / "glyphs") for p in sorted(folder.glob("*.svg"))}
    manifest = json.loads((OVERLAY / "manifest.json").read_text())
    validate(manifest, icons, glyphs)
    payload = {"width": 61, "height": 48, "icons": icons, "glyphs": glyphs,
               "skins": manifest["skins"], "aliases": manifest["aliases"]}
    index_payload = {
        "icons": list(icons.keys()),
        "glyphs": list(glyphs.keys()),
        "skins": manifest["skins"],
        "aliases": manifest["aliases"],
    }
    return (
        json.dumps(payload, separators=(",", ":")) + "\n",
        json.dumps(index_payload, separators=(",", ":")) + "\n",
        len(icons),
        len(glyphs),
    )


def main():
    payload, index_payload, icon_count, glyph_count = build()
    if "--check" in sys.argv[1:]:
        current = OUTPUT.read_text() if OUTPUT.exists() else ""
        current_index = INDEX_OUTPUT.read_text() if INDEX_OUTPUT.exists() else ""
        assert current == payload, f"{OUTPUT.relative_to(ROOT)} is stale; rebuild the vector catalog"
        assert current_index == index_payload, f"{INDEX_OUTPUT.relative_to(ROOT)} is stale; rebuild the vector catalog"
        print(f"Verified {icon_count} tiles and {glyph_count} glyphs in {OUTPUT.relative_to(ROOT)} and {INDEX_OUTPUT.relative_to(ROOT)}")
        return
    OUTPUT.write_text(payload)
    INDEX_OUTPUT.write_text(index_payload)
    print(f"Compiled {icon_count} tiles and {glyph_count} glyphs to {OUTPUT.relative_to(ROOT)} "
          f"({OUTPUT.stat().st_size:,} bytes) and {INDEX_OUTPUT.relative_to(ROOT)} ({INDEX_OUTPUT.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
