import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

MODULE_PATH = Path(__file__).resolve().parent / "metrofuse-plugin.py"
SPEC = importlib.util.spec_from_file_location("metrofuse_plugin", MODULE_PATH)
assert SPEC and SPEC.loader
cli = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = cli
SPEC.loader.exec_module(cli)


class MetroFusePluginToolkitTest(unittest.TestCase):
    def test_scaffold_defaults_to_api_1_2_and_packs_verifies(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            js_dir = root / "js-sample"
            cli.scaffold_plugin(js_dir, "example.fast.js", "Fast JS")
            manifest = json.loads((js_dir / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("1.2.0", manifest["api"])
            self.assertIn("definePlugin", (js_dir / "index.js").read_text(encoding="utf-8"))

            mfp = cli.pack_mfp(js_dir, root / "out" / "fast.mfp")
            summary = cli.verify_artifact(mfp)
            self.assertEqual("1.2.0", summary["api"])
            self.assertEqual("mfp", summary["kind"])

            kt_dir = root / "kt-sample"
            cli.scaffold_plugin(kt_dir, "example.fast.kt", "Fast Kotlin", runtime="kotlin")
            kt_manifest = json.loads((kt_dir / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("1.2.0", kt_manifest["api"])
            self.assertEqual(cli.COMPOSE_ABI, kt_manifest["composeAbi"])

    def test_packs_and_verifies_legacy_1_1_and_rejects_unsupported_apis(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            legacy_dir = root / "legacy11"
            cli.scaffold_plugin(legacy_dir, "example.legacy.js", "Legacy 1.1", runtime="js", api="1.1.0")
            mfp = cli.pack_mfp(legacy_dir, root / "legacy.mfp")
            self.assertEqual("1.1.0", cli.verify_artifact(mfp)["api"])

            for bad_api in ("1.0.0", "1.3.0", "2.0.0"):
                bad_dir = root / f"bad-{bad_api}"
                bad_dir.mkdir()
                (bad_dir / "index.js").write_text("globalThis.plugin = { init() {} };", encoding="utf-8")
                (bad_dir / "manifest.json").write_text(
                    json.dumps(
                        {
                            "id": f"example.bad.{bad_api.replace('.', '_')}",
                            "name": "Bad",
                            "version": "1.0.0",
                            "author": "Test",
                            "api": bad_api,
                            "runtime": "javascript",
                            "script": "index.js",
                        },
                    ),
                    encoding="utf-8",
                )
                with self.assertRaises(ValueError):
                    cli.pack_mfp(bad_dir, root / f"bad-{bad_api}.mfp")

    def test_verify_apk_enforces_stored_resources_arsc_for_1_2_0(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / "bad-120.apk"
            manifest = {
                "id": "example.native.stored",
                "name": "Stored Check",
                "version": "1.0.0",
                "author": "Test",
                "api": "1.2.0",
                "runtime": "kotlin",
                "entryClass": "example.StoredPlugin",
                "composeAbi": cli.COMPOSE_ABI,
            }
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as zf:
                zf.writestr("assets/metrofuse/manifest.json", json.dumps(manifest))
                zf.writestr("classes.dex", b"dex\n035\0")
                zf.writestr("resources.arsc", b"\x02\x00\x0c\x00")
            with self.assertRaises(ValueError):
                cli.verify_artifact(apk)

            good_apk = root / "good-120.apk"
            with zipfile.ZipFile(good_apk, "w", compression=zipfile.ZIP_DEFLATED) as zf:
                zf.writestr("assets/metrofuse/manifest.json", json.dumps(manifest))
                zf.writestr("classes.dex", b"dex\n035\0")
                zf.writestr(zipfile.ZipInfo("resources.arsc"), b"\x02\x00\x0c\x00", compress_type=zipfile.ZIP_STORED)
            self.assertEqual("apk", cli.verify_artifact(good_apk)["kind"])


if __name__ == "__main__":
    unittest.main()
