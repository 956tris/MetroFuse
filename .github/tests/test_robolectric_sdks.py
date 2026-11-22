"""Exercise SDK cache integrity and mirror failures without network requests."""

import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / '.github/scripts/prepare-robolectric-sdks.sh'


class RobolectricSdksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.fixtures = self.root / 'fixtures'
        self.fixtures.mkdir()
        self.destination = self.root / 'SDK cache'
        self.log = self.root / 'requests.txt'
        tools = self.root / 'bin'
        tools.mkdir()
        curl = tools / 'curl'
        curl.write_text('''#!/usr/bin/env bash
set -euo pipefail
output=
while (( $# )); do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    *) url="$1"; shift ;;
  esac
done
printf '%s\\n' "$url" >> "$SDK_REQUEST_LOG"
if [[ "${FAIL_ALL_MIRRORS:-0}" == 1 ||
      ( "${FAIL_PRIMARY_MIRROR:-0}" == 1 && "$url" == *storage-download.googleapis.com* ) ]]; then
  exit 22
fi
filename="${url##*/}"
if [[ "${CORRUPT_CHECKSUM:-0}" == 1 && "$filename" == *.sha512 ]]; then
  printf '%0128d\\n' 0 > "$output"
else
  cp "$SDK_FIXTURE_DIRECTORY/$filename" "$output"
fi
''', encoding='utf-8', newline='\n')
        curl.chmod(0o755)
        self.environment = {
            **os.environ,
            'SDK_TEST_BIN': f'/{tools.drive[0].lower()}{tools.as_posix()[2:]}' if os.name == 'nt' else tools.as_posix(),
            'SDK_FIXTURE_DIRECTORY': self.fixtures.as_posix(),
            'SDK_REQUEST_LOG': self.log.as_posix(),
        }
        self.filenames = []
        for version in re.findall(r'^\s+(\S+-i7)\s+# API', SCRIPT.read_text(), re.MULTILINE):
            filename = f'android-all-instrumented-{version}.jar'
            self.filenames.append(filename)
            contents = filename.encode()
            (self.fixtures / filename).write_bytes(contents)
            (self.fixtures / f'{filename}.sha512').write_text(hashlib.sha512(contents).hexdigest() + '\n')

    def prepare(self, **environment):
        return subprocess.run(
            ['bash', '-c', 'export PATH="$SDK_TEST_BIN:$PATH"; source "$1" "$2"',
             'prepare-sdks', SCRIPT.as_posix(), self.destination.as_posix()],
            capture_output=True, text=True, timeout=30,
            env={**self.environment, **environment},
        )

    def assert_prepared(self, result):
        self.assertEqual(0, result.returncode, result.stderr)
        for filename in self.filenames:
            self.assertEqual((self.fixtures / filename).read_bytes(), (self.destination / filename).read_bytes())

    def test_verified_cache_requires_no_downloads_on_retry(self):
        self.assert_prepared(self.prepare())
        requests = self.log.read_text()
        self.assert_prepared(self.prepare(FAIL_ALL_MIRRORS='1'))
        self.assertEqual(requests, self.log.read_text())

    def test_corrupt_cached_sdk_is_downloaded_again(self):
        self.assert_prepared(self.prepare())
        (self.destination / self.filenames[0]).write_bytes(b'corrupt cache entry')
        before = len(self.log.read_text().splitlines())
        self.assert_prepared(self.prepare())
        self.assertEqual(before + 2, len(self.log.read_text().splitlines()))

    def test_second_mirror_recovers_primary_download_failure(self):
        self.assert_prepared(self.prepare(FAIL_PRIMARY_MIRROR='1'))
        requests = self.log.read_text()
        self.assertIn('https://maven-central.storage-download.googleapis.com/maven2/', requests)
        self.assertIn('https://repo.maven.apache.org/maven2/', requests)

    def test_checksum_mismatch_never_installs_an_sdk(self):
        result = self.prepare(CORRUPT_CHECKSUM='1')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.destination / self.filenames[0]).exists())

    def test_all_mirrors_failing_stops_preparation(self):
        result = self.prepare(FAIL_ALL_MIRRORS='1')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.destination / self.filenames[0]).exists())


if __name__ == '__main__':
    unittest.main()
