# Static GitHub marketplace

The client is `dev.metrofuse.runtime.MarketplaceClient`. Settings → Plugins accepts an HTTPS URL pointing to registry JSON, for example an approved registry file's GitHub raw URL. No invented official repository URL is bundled. The selected URL persists in `Settings.shared.pluginMarketplaceUrl`. Offline imports work without this URL or network access.

Registry shape:

```json
{
  "schemaVersion": 1,
  "plugins": [{
    "id": "example.provider",
    "name": "Example provider",
    "author": "Plugin author",
    "description": "A provider maintained by its author",
    "version": "1.0.0",
    "pluginApiVersion": "1.1.0",
    "capabilities": ["audio", "metadata"],
    "categories": ["Music"],
    "sourceRepository": "https://github.com/your-organization/your-plugin",
    "releaseChannel": "stable",
    "downloads": {
      "desktop": { "url": "https://your-host/provider.jar", "sha256": "<64 hexadecimal SHA-256 characters>", "fileName": "provider.jar" },
      "android": { "url": "https://your-host/provider.apk", "sha256": "<64 hexadecimal SHA-256 characters>", "fileName": "provider.apk" }
    }
  }]
}
```

For JavaScript, replace the downloads map with one `universal` artifact named `provider.mfp`. The example URLs and checksum placeholders above explain the schema and are not downloadable artifacts. Compute the real checksum from the release artifact using `Get-FileHash -Algorithm SHA256`.

The marketplace lists entries by platform and category and searches names, descriptions, authors and IDs. The optional `categories` field groups plugins for browsing. Older registries can use `capabilities` as categories. Expanded details show the selected artifact, supported platforms, required plugin API and HTTPS source link when supplied. Install and update are available for compatible artifacts. The last successfully fetched catalog is cached in memory for that registry URL. Refresh replaces it after validation and keeps the previous catalog available if a refresh fails.

The client validates schema version, stable semantic versions, API compatibility, platform format, filenames and checksums. Downloads require HTTPS, reject protocol downgrade, use connection/read/call deadlines, and cap registry size at 2 MiB and artifacts at 64 MiB. Installation verifies downloaded SHA-256, stages and validates the artifact and checks manifest ID/version against the listing before changing the installed version. An updated active plugin follows the same lifecycle and rollback path as a local update; a new plugin starts disabled.

Marketplace authors submit registry changes through pull requests. Maintainers approve listings by reviewing and merging those changes; the static catalog contains approved entries. Manual imports remain available for unlisted authors. Checksums detect altered release bytes, not author trust or in-process native isolation.

Local automated tests cover platform artifact selection, shared universal artifacts, incompatible API filtering and invalid checksum/path/protocol rejection. Live public marketplace hosting and approval administration are outside this implementation.
