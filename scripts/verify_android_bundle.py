#!/usr/bin/env python3
"""Reject JVM-only or incomplete MPP artifacts before Android distribution."""

import re
import struct
import sys
import zipfile


def dex_classes(data):
    if len(data) < 112 or not re.fullmatch(rb"dex\n0[0-9]{2}\x00", data[:8]):
        raise ValueError("invalid DEX header")
    if struct.unpack_from("<I", data, 32)[0] != len(data):
        raise ValueError("invalid DEX file size")

    def uint(offset):
        return struct.unpack_from("<I", data, offset)[0]

    strings = uint(60)
    types = uint(68)
    count, definitions = uint(96), uint(100)
    result = set()
    for index in range(count):
        type_index = uint(definitions + index * 32)
        string_index = uint(types + type_index * 4)
        offset = uint(strings + string_index * 4)
        # Skip the ULEB128 UTF-16 length preceding the descriptor string.
        for _ in range(5):
            byte = data[offset]
            offset += 1
            if byte < 128:
                break
        else:
            raise ValueError("invalid DEX string length")
        end = data.index(b"\x00", offset)
        descriptor = data[offset:end].decode("utf-8", errors="surrogatepass")
        if not descriptor.startswith("L") or not descriptor.endswith(";"):
            raise ValueError("invalid DEX class descriptor")
        result.add(descriptor[1:-1])
    return result


def verify(path):
    with zipfile.ZipFile(path) as bundle:
        names = bundle.namelist()
        if "classes.dex" not in names:
            raise ValueError("missing root classes.dex: this bundle cannot load patches on Android")
        dex = set()
        for name in names:
            if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                classes = dex_classes(bundle.read(name))
                if dex.intersection(classes):
                    raise ValueError("duplicate DEX class definitions")
                dex.update(classes)
        expected = {name[:-6] for name in names if name.endswith(".class")
                    and not name.startswith("META-INF/") and name != "module-info.class"}
        if not expected or not dex:
            raise ValueError("empty patch code")
        missing = expected - dex
        if missing:
            raise ValueError(f"{len(missing)} JVM classes missing from Android DEX: {sorted(missing)[:5]}")
        print(f"{path}: Android bundle verified ({len(expected)} JVM classes, {len(dex)} DEX classes)")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("Usage: verify_android_bundle.py <bundle.mpp> [...]")
    try:
        for path in sys.argv[1:]:
            verify(path)
    except (ValueError, IndexError, struct.error, zipfile.BadZipFile, OSError) as error:
        sys.exit(f"Android bundle verification failed: {error}")
