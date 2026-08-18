#!/usr/bin/env python3
"""Reject ONNX Runtime versions that cannot load the bundled sherpa JNI."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_FILE = ROOT / "app" / "build.gradle.kts"
JNI_ROOT = ROOT / "app" / "src" / "main" / "jniLibs"


def declared_runtime_version() -> str:
    content = BUILD_FILE.read_text(encoding="utf-8")
    match = re.search(
        r'com\.microsoft\.onnxruntime:onnxruntime-android:([0-9]+\.[0-9]+\.[0-9]+)',
        content,
    )
    if not match:
        raise AssertionError("ONNX Runtime Android dependency was not found")
    return match.group(1)


def required_runtime_versions(library: Path) -> set[str]:
    data = library.read_bytes()
    if b"OrtGetApiBase" not in data:
        raise AssertionError(f"{library} does not import OrtGetApiBase")
    return {
        match.decode("ascii")
        for match in re.findall(rb"VERS_([0-9]+\.[0-9]+\.[0-9]+)", data)
    }


def main() -> None:
    declared = declared_runtime_version()
    libraries = sorted(JNI_ROOT.glob("*/libsherpa-onnx-jni.so"))
    if not libraries:
        raise AssertionError("No sherpa JNI libraries were found")

    for library in libraries:
        required = required_runtime_versions(library)
        if required != {declared}:
            relative = library.relative_to(ROOT)
            raise AssertionError(
                f"{relative} requires {sorted(required)}, but Gradle packages "
                f"ONNX Runtime {declared}"
            )

    print(
        f"Native runtime compatibility passed: {len(libraries)} sherpa ABIs "
        f"require ONNX Runtime {declared}."
    )


if __name__ == "__main__":
    main()
