# Install MetroFuse

After installation, follow the [MetroFuse user guide](../user-guide.md) to scan local music, import provider or UI plugins and configure playback. Plugin MFP/JAR/APK packages are imported through **Settings → Plugins**, separately from the application installer.

Choose your platform: [Android](https://github.com/956tris/MetroFuse/blob/main/docs/install/android.md) or [Windows](https://github.com/956tris/MetroFuse/blob/main/docs/install/windows.md).

## Get the package

Use [MetroFuse releases](https://github.com/956tris/MetroFuse/releases) for downloads. The table describes stable release workflow outputs.

The [release workflow](../../.github/workflows/release.yml) defines these packages:

| Platform | Release asset |
| --- | --- |
| Android | `MetroFuse-Android-Release.apk` |
| Windows x64 | `MetroFuse-Desktop-Windows.zip` |

Checksum verification is published in `SHA256SUMS.txt` on every release.

## Verify a download

Download `SHA256SUMS.txt` from the same release. Calculate the package's SHA-256 digest before opening it. Enter the full path when prompted.

On Linux:

```sh
printf 'Downloaded file path: '
read -r package
sha256sum -- "$package"
```

On macOS:

```sh
printf 'Downloaded file path: '
read -r package
shasum -a 256 -- "$package"
```

On Windows, use PowerShell:

```powershell
$package = Read-Host 'Downloaded file path'
Get-FileHash -Algorithm SHA256 -LiteralPath $package
```

Compare all 64 hexadecimal characters with the matching entry in the checksum file. Letter case does not matter.

A mismatch means you should download the file again. Do not install a mismatched file. If the publisher supplies no checksum, you cannot perform this comparison. A checksum confirms that the file matches the publisher's copy; it is not a code-signing certificate.

For Android, you can verify the APK on a computer before transferring it unchanged to the device. After installation, check the version in MetroFuse's settings, open a song and confirm audible playback. Platform guides add checks for login and signing.

## Before updating or uninstalling

Export a backup from MetroFuse's settings and keep it outside the installation directory or mobile app storage. Keep downloaded music separately if you need it. Do not assume a backup includes every downloaded audio file.

Use the same platform, architecture and installation method for updates. Avoid downgrading a database created by a newer version; see [downgrade safety](https://github.com/956tris/MetroFuse/blob/main/docs/crash-recovery.md). Uninstalling a mobile app deletes its local data. Removing a desktop application can leave settings, credentials, caches and downloaded music behind.

If installation or startup still fails, include the package filename, app version, OS version, CPU architecture and exact error in a report to the [issue tracker](https://github.com/956tris/MetroFuse/issues). Remove account details and tokens before sharing logs.
