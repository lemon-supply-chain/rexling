#!/usr/bin/env python3
"""Test the real JNI bridge on the host JVM; never uses an Android device."""
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT.parent / "rexling-core"


def run(command, **kwargs):
    subprocess.run([str(v) for v in command], check=True, cwd=ROOT, **kwargs)


def main():
    run([sys.executable, ROOT / "scripts/build_native.py", "--host-test"])
    env = os.environ.copy()
    env["GHOSTTY_PREFIX"] = str(CORE / ".build/ghostty-host")
    env["CARGO_TARGET_DIR"] = str(CORE / ".build/rust")
    run([shutil.which("cargo") or str(Path.home() / ".cargo/bin/cargo"), "build", "--locked", "--manifest-path", str(CORE / "Cargo.toml"), "-p", "rexling-jni"], env=env)
    studio = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    jdk = studio if studio.exists() else Path(env["JAVA_HOME"])
    output = ROOT / ".build/jni-smoke"
    output.mkdir(parents=True, exist_ok=True)
    run([jdk / "bin/javac", "-encoding", "UTF-8", "-d", output, ROOT / "tools/jni-smoke/dev/lemon/rex/NativeBridge.java"])
    extension = "dylib" if platform.system() == "Darwin" else "so"
    library = CORE / f".build/rust/debug/librex_android.{extension}"
    run([jdk / "bin/java", "--enable-native-access=ALL-UNNAMED", f"-Drex.native.library={library}", "-cp", output, "dev.lemon.rex.NativeBridge"])


if __name__ == "__main__":
    main()
