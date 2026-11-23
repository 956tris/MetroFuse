#!/usr/bin/env python3
"""MetroFuse Plugin Toolkit — fast scaffolding, packing, watching, and verification for API 1.1.0 & 1.2.0 plugins."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import zipfile

LEGACY_API_VERSION_1_1 = "1.1.0"
LEGACY_COMPOSE_ABI_1_1 = "kotlin-2.4.21_compose-1.13.0-beta02_api-1.1.0"
API_VERSION = "1.2.0"
COMPOSE_ABI = "kotlin-2.4.21_compose-1.13.0-beta02_api-1.2.0"

SUPPORTED_API_TO_ABI = {
    LEGACY_API_VERSION_1_1: LEGACY_COMPOSE_ABI_1_1,
    API_VERSION: COMPOSE_ABI,
}
SUPPORTED_APIS = frozenset(SUPPORTED_API_TO_ABI)
SUPPORTED_ABIS = frozenset(SUPPORTED_API_TO_ABI.values())
VALID_PLUGIN_ID = re.compile(r"^[a-z][a-z0-9.-]{2,79}$")
VALID_CLASS_NAME = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def validate_plugin_id(plugin_id: str) -> None:
    if not VALID_PLUGIN_ID.match(plugin_id) or ".." in plugin_id or plugin_id == "builtin" or plugin_id.startswith("builtin."):
        raise ValueError(f"Invalid plugin ID: {plugin_id}")


def validate_rel_path(rel_path: str) -> None:
    if not rel_path or len(rel_path) > 220 or "\\" in rel_path or ":" in rel_path or rel_path.startswith("/"):
        raise ValueError(f"Unsafe package path: {rel_path}")
    segments = rel_path.split("/")
    if any(seg in ("", ".", "..") for seg in segments):
        raise ValueError(f"Unsafe package path segments: {rel_path}")


def scaffold_plugin(
    target_dir: Path,
    plugin_id: str,
    name: str,
    author: str = "MetroFuse Contributor",
    runtime: str = "js",
    api: str = API_VERSION,
    capabilities: list[str] | None = None,
) -> Path:
    validate_plugin_id(plugin_id)
    if api not in SUPPORTED_APIS:
        raise ValueError(f"Unsupported API version {api}; choose one of {sorted(SUPPORTED_APIS)}")
    caps = capabilities if capabilities is not None else ["audio", "search"]
    target_dir.mkdir(parents=True, exist_ok=True)

    if runtime in ("js", "javascript"):
        manifest = {
            "id": plugin_id,
            "name": name,
            "version": "1.0.0",
            "author": author,
            "api": api,
            "runtime": "javascript",
            "platforms": ["desktop", "android"],
            "script": "index.js",
            "capabilities": caps,
            "description": f"{name} MetroFuse plugin",
        }
        (target_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        if api == LEGACY_API_VERSION_1_1:
            script_body = (
                "globalThis.plugin = {\n"
                "  async init(host) {\n"
                f"    host.log('{name} initialized (API 1.1.0)');\n"
                "  }\n"
                "};\n"
            )
        else:
            script_body = (
                "definePlugin({\n"
                "  setup(host) {\n"
                f"    host.log('{name} initialized (API 1.2.0)');\n"
                "  },\n"
                "  search: {\n"
                "    'default': async ({ query }) => ({ tracks: [] })\n"
                "  }\n"
                "});\n"
            )
        (target_dir / "index.js").write_text(script_body, encoding="utf-8")
    elif runtime in ("kotlin", "native"):
        pkg = re.sub(r"[^a-zA-Z0-9.]", "", plugin_id)
        class_name = "MainPlugin"
        entry_class = f"{pkg}.{class_name}"
        manifest = {
            "id": plugin_id,
            "name": name,
            "version": "1.0.0",
            "author": author,
            "api": api,
            "runtime": "kotlin",
            "platforms": ["desktop", "android"],
            "entryClass": entry_class,
            "composeAbi": SUPPORTED_API_TO_ABI[api],
            "capabilities": caps,
            "description": f"{name} native MetroFuse plugin",
        }
        (target_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        src_dir = target_dir / "src" / "main" / "kotlin" / Path(*pkg.split("."))
        src_dir.mkdir(parents=True, exist_ok=True)
        if api == LEGACY_API_VERSION_1_1:
            kt_body = (
                f"package {pkg}\n\n"
                "import dev.metrofuse.api.NativePlugin\n"
                "import dev.metrofuse.api.PluginContext\n\n"
                f"class {class_name} : NativePlugin {{\n"
                "    override suspend fun start(context: PluginContext) {\n"
                f"        context.log(\"{name} started\")\n"
                "    }\n"
                "}\n"
            )
        else:
            kt_body = (
                f"package {pkg}\n\n"
                "import dev.metrofuse.sdk.MetroFusePlugin\n"
                "import dev.metrofuse.sdk.PluginBuilder\n\n"
                f"class {class_name} : MetroFusePlugin() {{\n"
                "    override suspend fun PluginBuilder.setup() {\n"
                f"        context.log(\"{name} started\")\n"
                "    }\n"
                "}\n"
            )
        (src_dir / f"{class_name}.kt").write_text(kt_body, encoding="utf-8")
    else:
        raise ValueError(f"Unsupported runtime {runtime}; choose js or kotlin")

    return target_dir


def pack_mfp(source_dir: Path, output_file: Path) -> Path:
    source_root = source_dir.resolve()
    manifest_path = source_root / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Missing manifest.json in {source_root}")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_plugin_id(manifest["id"])
    api = manifest.get("api")
    if manifest.get("runtime") != "javascript" or api not in SUPPORTED_APIS:
        raise ValueError(
            f"pack supports JavaScript plugins targeting API {sorted(SUPPORTED_APIS)}; "
            f"got runtime={manifest.get('runtime')!r}, api={api!r}",
        )

    files = sorted(
        (p for p in source_root.rglob("*") if p.is_file() and p != manifest_path),
        key=lambda p: p.relative_to(source_root).as_posix(),
    )
    integrity: dict[str, str] = {}
    payloads: list[tuple[str, bytes]] = []
    for file_path in files:
        rel = file_path.relative_to(source_root).as_posix()
        validate_rel_path(rel)
        if rel.lower().endswith((".jar", ".apk", ".dex")):
            raise ValueError(f"JavaScript plugin cannot contain native binary {rel}")
        raw = file_path.read_bytes()
        integrity[rel] = sha256_bytes(raw)
        payloads.append((rel, raw))

    script = manifest.get("script")
    if not script or script not in integrity:
        raise ValueError(f"Manifest script entrypoint {script!r} is missing")

    manifest["integrity"] = integrity
    manifest_bytes = json.dumps(manifest, separators=(",", ":"), ensure_ascii=False).encode("utf-8")

    output_path = output_file.resolve()
    if output_path.suffix.lower() != ".mfp":
        raise ValueError("Output file must use the .mfp extension")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        info = zipfile.ZipInfo("manifest.json", date_time=FIXED_ZIP_TIME)
        archive.writestr(info, manifest_bytes)
        for rel, raw in payloads:
            entry_info = zipfile.ZipInfo(rel, date_time=FIXED_ZIP_TIME)
            archive.writestr(entry_info, raw)
    return output_path


def verify_artifact(artifact_path: Path, require_stored_arsc: bool = False) -> dict[str, object]:
    path = artifact_path.resolve()
    if not path.is_file():
        raise FileNotFoundError(f"Artifact not found: {path}")
    ext = path.suffix.lower()
    if ext not in (".mfp", ".jar", ".apk"):
        raise ValueError(f"Unsupported plugin package extension: {ext}")

    with zipfile.ZipFile(path, "r") as archive:
        names = set(archive.namelist())
        if ext == ".mfp":
            if "manifest.json" not in names:
                raise ValueError("Missing root manifest.json in .mfp archive")
            manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
            _validate_common_manifest(manifest, expected_runtime="javascript")
            integrity = manifest.get("integrity")
            if not isinstance(integrity, dict):
                raise ValueError("Missing integrity map in manifest.json")
            payload_names = sorted(n for n in names if not n.endswith("/") and n != "manifest.json")
            if set(integrity.keys()) != set(payload_names):
                raise ValueError("Manifest integrity keys do not match archive payload")
            for rel in payload_names:
                validate_rel_path(rel)
                if rel.lower().endswith((".jar", ".apk", ".dex")):
                    raise ValueError(f"JavaScript archive cannot contain {rel}")
                actual = sha256_bytes(archive.read(rel))
                if actual != integrity[rel]:
                    raise ValueError(f"SHA-256 mismatch for {rel}")
            if manifest.get("script") not in payload_names:
                raise ValueError(f"Missing script entrypoint {manifest.get('script')!r}")
            return {"kind": "mfp", "id": manifest["id"], "api": manifest["api"], "files": len(payload_names)}

        if ext == ".jar":
            if "manifest.json" not in names:
                raise ValueError("Missing root manifest.json in native JAR")
            manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
            _validate_common_manifest(manifest, expected_runtime="kotlin")
            entry_class_file = manifest["entryClass"].replace(".", "/") + ".class"
            if entry_class_file not in names:
                raise ValueError(f"Entry class {entry_class_file} missing from native JAR")
            return {"kind": "jar", "id": manifest["id"], "api": manifest["api"], "composeAbi": manifest["composeAbi"]}

        # Native Android APK
        manifest_entry = next(
            (n for n in ("assets/metrofuse/manifest.json", "manifest.json") if n in names),
            None,
        )
        if manifest_entry is None:
            raise ValueError("Missing assets/metrofuse/manifest.json or manifest.json in native APK")
        manifest = json.loads(archive.read(manifest_entry).decode("utf-8"))
        _validate_common_manifest(manifest, expected_runtime="kotlin")
        if not any(n.endswith(".dex") and "/" not in n for n in names):
            raise ValueError("Native APK does not contain any root classes.dex")
        arsc_warnings: list[str] = []
        if "resources.arsc" in names:
            arsc_info = archive.getinfo("resources.arsc")
            if arsc_info.compress_type != zipfile.ZIP_STORED:
                msg = "resources.arsc is compressed (DEFLATED); Android 11+ recommends ZIP_STORED"
                if require_stored_arsc or manifest.get("api") == API_VERSION:
                    raise ValueError(msg)
                arsc_warnings.append(msg)
        return {
            "kind": "apk",
            "id": manifest["id"],
            "api": manifest["api"],
            "composeAbi": manifest["composeAbi"],
            "warnings": arsc_warnings,
        }


def _validate_common_manifest(manifest: dict, expected_runtime: str) -> None:
    if "api" not in manifest:
        raise ValueError("Manifest must declare api")
    api = manifest["api"]
    if api not in SUPPORTED_APIS:
        raise ValueError(f"Unsupported plugin API {api!r}; supported: {sorted(SUPPORTED_APIS)}")
    validate_plugin_id(manifest["id"])
    if manifest.get("runtime") != expected_runtime:
        raise ValueError(f"Expected runtime {expected_runtime!r}, got {manifest.get('runtime')!r}")
    if expected_runtime == "kotlin":
        entry = manifest.get("entryClass")
        if not isinstance(entry, str) or not VALID_CLASS_NAME.match(entry):
            raise ValueError(f"Invalid entryClass: {entry!r}")
        if manifest.get("composeAbi") not in SUPPORTED_ABIS:
            raise ValueError(f"Unsupported composeAbi: {manifest.get('composeAbi')!r}")


def _snapshot_dir(source_dir: Path) -> dict[str, tuple[int, int]]:
    snap: dict[str, tuple[int, int]] = {}
    for p in source_dir.rglob("*"):
        if p.is_file():
            st = p.stat()
            snap[p.relative_to(source_dir).as_posix()] = (st.st_mtime_ns, st.st_size)
    return snap


def watch_mfp(
    source_dir: Path,
    output_file: Path,
    interval: float = 0.4,
    adb_push: str | None = None,
    once: bool = False,
) -> None:
    last_snap: dict[str, tuple[int, int]] | None = None
    while True:
        snap = _snapshot_dir(source_dir)
        if snap != last_snap:
            t0 = time.perf_counter()
            out = pack_mfp(source_dir, output_file)
            elapsed_ms = (time.perf_counter() - t0) * 1000.0
            print(f"[metrofuse-plugin] Packed {out.name} in {elapsed_ms:.1f} ms")
            if adb_push:
                subprocess.run(["adb", "push", str(out), adb_push], check=True)
            last_snap = snap
        if once:
            break
        time.sleep(interval)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="metrofuse-plugin", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_new = sub.add_parser("new", help="Scaffold a new plugin (defaults to Plugin API 1.2.0)")
    p_new.add_argument("directory", type=Path)
    p_new.add_argument("--id", required=True)
    p_new.add_argument("--name", required=True)
    p_new.add_argument("--author", default="MetroFuse Contributor")
    p_new.add_argument("--runtime", choices=["js", "javascript", "kotlin", "native"], default="js")
    p_new.add_argument("--api", default=API_VERSION)
    p_new.add_argument("--capabilities", nargs="*", default=None)

    p_pack = sub.add_parser("pack", help="Pack a JS plugin directory into a .mfp archive")
    p_pack.add_argument("source", type=Path)
    p_pack.add_argument("output", type=Path)

    p_verify = sub.add_parser("verify", help="Verify a .mfp, .jar, or .apk plugin package")
    p_verify.add_argument("artifact", type=Path)
    p_verify.add_argument("--require-stored-arsc", action="store_true")

    p_watch = sub.add_parser("watch", help="Watch a JS plugin directory and repack .mfp on edits")
    p_watch.add_argument("source", type=Path)
    p_watch.add_argument("output", type=Path)
    p_watch.add_argument("--interval", type=float, default=0.4)
    p_watch.add_argument("--adb-push", default=None)
    p_watch.add_argument("--once", action="store_true")

    args = parser.parse_args(argv)
    if args.command == "new":
        created = scaffold_plugin(
            args.directory,
            plugin_id=args.id,
            name=args.name,
            author=args.author,
            runtime=args.runtime,
            api=args.api,
            capabilities=args.capabilities,
        )
        print(created)
        return 0
    if args.command == "pack":
        t0 = time.perf_counter()
        out = pack_mfp(args.source, args.output)
        print(f"{out} ({(time.perf_counter() - t0) * 1000.0:.1f} ms)")
        return 0
    if args.command == "verify":
        summary = verify_artifact(args.artifact, require_stored_arsc=args.require_stored_arsc)
        print(json.dumps(summary))
        return 0
    if args.command == "watch":
        watch_mfp(args.source, args.output, interval=args.interval, adb_push=args.adb_push, once=args.once)
        return 0
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
