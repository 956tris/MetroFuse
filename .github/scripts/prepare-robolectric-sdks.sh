#!/usr/bin/env bash
set -euo pipefail

destination="${1:?Provide a directory for the Robolectric SDKs}"
mkdir -p "$destination"

# Robolectric 4.17 SDKs used by the host tests, plus the latest SDK for resources.
versions=(
  7.0.0_r1-robolectric-r1-i7       # API 24
  8.1.0-robolectric-4611349-i7     # API 27
  10-robolectric-5803371-i7        # API 29
  12-robolectric-7732740-i7        # API 31
  12.1-robolectric-8229987-i7      # API 32
  13-robolectric-9030017-i7       # API 33
  14-robolectric-10818077-i7      # API 34
  16-robolectric-13921718-i7      # API 36
  17-robolectric-15733970-i7      # API 37
)

fetch() {
  local artifact="$1" output="$2" repository
  # Google's Maven Central mirror avoids Central's shared-runner rate limit.
  for repository in \
    https://maven-central.storage-download.googleapis.com/maven2 \
    https://repo.maven.apache.org/maven2; do
    if curl --fail --silent --show-error --location --retry 4 --retry-all-errors \
      --retry-max-time 120 --connect-timeout 15 --max-time 180 \
      --output "$output" "$repository/$artifact"; then
      return 0
    fi
  done
  return 1
}

for version in "${versions[@]}"; do
  filename="android-all-instrumented-$version.jar"
  jar="$destination/$filename"
  checksum="$jar.sha512"
  if [[ -s "$jar" && -s "$checksum" ]] &&
    printf '%s  %s\n' "$(cat "$checksum")" "$jar" | sha512sum --check --status; then
    continue
  fi

  echo "Downloading Robolectric SDK $version"
  artifact="org/robolectric/android-all-instrumented/$version/$filename"
  fetch "$artifact.sha512" "$checksum.download"
  expected_hash="$(tr -d '[:space:]' < "$checksum.download")"
  [[ "$expected_hash" =~ ^[a-fA-F0-9]{128}$ ]]
  fetch "$artifact" "$jar.download"
  printf '%s  %s\n' "$expected_hash" "$jar.download" | sha512sum --check
  printf '%s\n' "$expected_hash" > "$checksum"
  mv "$jar.download" "$jar"
  rm "$checksum.download"
done
