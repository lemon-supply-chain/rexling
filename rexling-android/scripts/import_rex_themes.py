#!/usr/bin/env python3
"""Project numeric colors from an installed Rex catalog; Android builds use the checked-in result."""
import argparse,json,hashlib
from pathlib import Path

from derive_theme_roles import derived_roles, ensure_readable

def rgb(p):
    # Display-P3 and sRGB use the same transfer function. Convert linear primaries,
    # then encode and clip to the sRGB gamut used by our Android terminal renderer.
    v=[p[k]/12.92 if p[k]<=.04045 else ((p[k]+.055)/1.055)**2.4 for k in ("red","green","blue")]
    matrix=((1.224940176,-.224940176,0),(-.042056955,1.042056955,0),(-.019637555,-.078636046,1.098273601))
    channels=[sum(a*b for a,b in zip(row,v)) for row in matrix]
    return "#"+"".join(f"{round(255*max(0,min(1,12.92*c if c<=.0031308 else 1.055*c**(1/2.4)-.055))):02x}" for c in channels)

def contrast(a,b):
    def luminance(h):
        v=[int(h[i:i+2],16)/255 for i in (1,3,5)]
        v=[c/12.92 if c<=.04045 else ((c+.055)/1.055)**2.4 for c in v]
        return sum(c*w for c,w in zip(v,(.2126,.7152,.0722)))
    a,b=luminance(a),luminance(b)
    return (max(a,b)+.05)/(min(a,b)+.05)

def main():
    p=argparse.ArgumentParser();p.add_argument("source",type=Path);args=p.parse_args()
    source=args.source.read_bytes();themes=json.loads(source)
    keys=("canvas","surface","surfaceSubtle","surfaceRaised","strokeSubtle","stroke","content","contentSecondary","accentSolid","accentOnSolid","accentContainer","accentOnContainer","dangerSolid","dangerOnSolid","dangerContainer","dangerOnContainer")
    result=[]
    for t in themes:
        s=t["semantic"]
        result.append({"id":t["id"],"name":t["name"],"dark":t["appearance"]=="dark",
            "background":rgb(s["terminalBackground"]),"foreground":rgb(s["terminalForeground"]),"cursor":rgb(s["terminalCursor"]),
            "cursorText":t["source"]["cursorText"],"ansi":[rgb(c) for c in t["palette"]],"ui":{k:rgb(s[k]) for k in keys}})
    for theme in result:
        ui=theme["ui"]
        for ink,background in [("accentOnSolid","accentSolid"),("dangerOnSolid","dangerSolid")]:
            if contrast(ui[ink],ui[background])<4.5:
                ui[ink]=max(("#000000","#ffffff"),key=lambda c:contrast(c,ui[background]))
        # Authored inks keep hue but must clear AA on the surfaces they sit on.
        ensure_readable(ui)
        # Selection and working need their own accents; see derive_theme_roles.py.
        ui.update(derived_roles(ui,theme["dark"]))
    assert len({t["id"] for t in result})==len(result) and all(len(t["ansi"])==256 for t in result)
    target=Path(__file__).resolve().parents[1]/"app/src/main/assets/themes";target.mkdir(parents=True,exist_ok=True)
    (target/"rex-builtins.json").write_text(json.dumps(result,separators=(",",":"))+"\n")
    (target/"SOURCE.json").write_text(json.dumps({"source":"Rex Beta.app / SuperUI_SuperDesignTokens.bundle / themes.json","sha256":hashlib.sha256(source).hexdigest(),"themes":len(result),"projection":"Numeric terminal and UI colors only; Display-P3 converted and clipped to sRGB. No network or Mac installation required at runtime.","derived_roles":"accentSecondary and accentTertiary triads are derived from each authored accent in OKLCH; see scripts/derive_theme_roles.py.","conversion_reference":"https://www.w3.org/TR/css-color-4/#color-conversion-code"},indent=2)+"\n")
if __name__=="__main__":main()
