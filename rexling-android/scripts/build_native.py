#!/usr/bin/env python3
"""Build the Rust workspace from the Android Gradle entry point."""
from pathlib import Path
import subprocess,sys
script=Path(__file__).resolve().parents[2]/"rexling-core/scripts/build_native.py"
subprocess.run([sys.executable,str(script),*sys.argv[1:]],check=True)
