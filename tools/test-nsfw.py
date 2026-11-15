"""Run host tests without Android, Gradle registry credentials, or device access.

Only downloads pinned Maven Central libraries into ignored build/host-tests.
"""
import hashlib
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build" / "host-tests"
ARTIFACTS = (
    "org/json/json/20240303/json-20240303.jar",
    "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar",
    "com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar",
    "org/jetbrains/kotlin/kotlin-stdlib/1.8.21/kotlin-stdlib-1.8.21.jar",
    "org/jetbrains/annotations/13.0/annotations-13.0.jar",
)


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    jars = []
    for artifact in ARTIFACTS:
        destination = BUILD / artifact.rsplit("/", 1)[1]
        url = "https://repo.maven.apache.org/maven2/" + artifact
        with urllib.request.urlopen(url + ".sha1", timeout=60) as response:
            expected = response.read().decode().strip().split()[0]
        if not destination.exists() or hashlib.sha1(destination.read_bytes()).hexdigest() != expected:
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            if hashlib.sha1(data).hexdigest() != expected:
                raise RuntimeError("Maven checksum mismatch: " + artifact)
            destination.write_bytes(data)
        jars.append(str(destination))
    import os
    classpath = os.pathsep.join(jars)
    sources = list((ROOT / "extensions/reddit/src/main/java/app/morphe/extension/reddit/patches/nsfw").glob("*.java"))
    sources += list((ROOT / "tests/nsfw").glob("*.java"))
    classes = BUILD / "classes"
    classes.mkdir(exist_ok=True)
    for executable in ("javac", "java"):
        if not shutil.which(executable):
            raise RuntimeError("A JDK with " + executable + " on PATH is required")
    subprocess.run(["javac", "--release", "17", "-cp", classpath, "-d", str(classes),
                    *map(str, sources)], check=True)
    subprocess.run(["java", "-ea", "-cp", str(classes) + os.pathsep + classpath,
                    "app.morphe.extension.reddit.patches.nsfw.NsfwTests"], check=True)


if __name__ == "__main__":
    main()
