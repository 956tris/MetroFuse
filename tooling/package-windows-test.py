"""Add test examples to the existing Gradle-built Windows portable package."""

import argparse
import hashlib
import io
import math
from pathlib import Path
import shutil
import struct
import wave
import zipfile


def test_audio():
    rate = 44100
    frames = rate * 3
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(rate)
        samples = bytearray()
        for index in range(frames):
            fade = min(1.0, index / 441, (frames - index - 1) / 441)
            samples.extend(struct.pack("<h", round(4000 * fade * math.sin(2 * math.pi * 440 * index / rate))))
        audio.writeframes(samples)
    return output.getvalue()


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-package", type=Path, default=root / "dist/Metrofuse-Alpha-Desktop-Windows-Debug.zip")
    parser.add_argument("--output", type=Path, default=root / "dist/Metrofuse-Alpha-Windows-Test.zip")
    args = parser.parse_args()
    source = args.app_package.resolve()
    destination = args.output.resolve()
    if source == destination:
        parser.error("The test bundle must use a separate output path")
    examples = {
        f"examples/{name}.mfp": root / f"build/plugin-examples/{name}.mfp"
        for name in ("minimal", "metadata", "lyrics", "settings", "jiosaavn")
    }
    examples.update({
        "examples/jiosaavn.jar": root / "build/plugin-examples/jiosaavn.jar",
    })
    examples.update({
        f"examples/native-{name}.jar": root / f"plugins/native-examples/build/outputs/plugins/native-{name}.jar"
        for name in ("minimal", "audio", "settings")
    })
    examples.update({"GETTING-STARTED.txt": root / "docs/windows-test-build.md", "METROFUSE-LICENSE.txt": root / "LICENSE"})
    for file in (source, *examples.values()):
        if not file.is_file():
            raise FileNotFoundError(file)
    with zipfile.ZipFile(source) as archive:
        if "Metrofuse Alpha.exe" not in archive.namelist():
            raise ValueError("Expected the Metrofuse Alpha Windows portable launcher at the archive root")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    with zipfile.ZipFile(destination, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, file in examples.items():
            archive.write(file, name)
        archive.writestr("examples/test-tone.wav", test_audio())
        archive.writestr("examples/Show-Audio-URI.cmd", "@echo off\r\nset \"METROFUSE_TEST_AUDIO_DIR=%~dp0\"\r\npowershell.exe -NoProfile -Command \"([uri](Join-Path $env:METROFUSE_TEST_AUDIO_DIR 'test-tone.wav')).AbsoluteUri\"\r\npause\r\n")
    with destination.open("rb") as bundle:
        digest = hashlib.file_digest(bundle, "sha256").hexdigest()
    destination.with_suffix(".zip.sha256").write_text(f"{digest}  {destination.name}\n", encoding="utf-8")
    print(f"{destination}\n{destination.stat().st_size:,} bytes\nSHA-256 {digest}")


if __name__ == "__main__":
    main()
