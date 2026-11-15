"""Test the irreversible preference using only Python and JDK 17+. No downloads or device access."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
classes = ROOT / "build/host-tests/classes"
classes.mkdir(parents=True, exist_ok=True)
source = ROOT / "extensions/reddit/src/main/java/app/morphe/extension/reddit/patches/nsfw/PermanentNsfwDisabler.java"
subprocess.run(["javac", "--release", "17", "-d", str(classes), str(source),
                str(ROOT / "tests/nsfw/NsfwTests.java")], check=True)
subprocess.run(["java", "-ea", "-cp", str(classes),
                "app.morphe.extension.reddit.patches.nsfw.NsfwTests"], check=True)
