#!/usr/bin/env python3
"""Build the Rust workspace from the Android Gradle entry point."""
from pathlib import Path
import subprocess,sys
destination = Path(__file__).resolve().parents[1]/"app/src/main/jniLibs/arm64-v8a"
if (destination / "librex_android.so").exists() and (destination / "libc++_shared.so").exists():
    sys.exit(0)
script=Path(__file__).resolve().parents[2]/"rexling-core/scripts/build_native.py"
subprocess.run([sys.executable,str(script),*sys.argv[1:]],check=True)
