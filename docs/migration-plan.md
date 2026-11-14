# MetroFuse KMP migration

## Reproducible baseline

The supplied archive matches MetroList commit `e97197786c2a7ad56279cc9bb4ce3233ef6c99ff`. A local Git repository was initialized on `codex/metrofuse-dual-runtime`, populated from that commit without replacing source files. Initial status showed no prior source changes. Submodules are pinned to `metroproto` `c65557e90e90c23e62101e69bbdc98af9f6c7ce5` and libopus `a8b2545eec71e0f53ecead2d583e7b6baa42684d`. No new commits or pushes are authorized.

## Implementation order

1. Audit startup, resolver, player, settings, resources and existing MetroFuse prototypes.
2. Reuse `dev.metrofuse.api` contracts and the existing QuickJS engine from the local `New folder (4)` prototype. Remove demo defaults and native/hybrid `.mfp` packaging. Compile these modules independently.
3. Implement direct native JAR/APK import, immutable executable loading, lifecycle validation and developer examples.
4. Integrate long-lived QuickJS contexts, isolated settings/storage, networking, search and lyrics. Run actual engine tests.
5. Wrap the existing StreamResolver and attach the existing platform PlaybackController. Preserve Media3, FFmpeg, SABR, queues, downloads and existing artwork/lyrics features.
6. Add a plugin manager to existing Android and desktop Settings, with platform file pickers, lifecycle controls, provider order, diagnostics, settings and plugin UI.
7. Add the static marketplace schema, artifact selection, bounded downloads and SHA-256 verification. Local installation remains independent.
8. Build example JAR/APK/MFP artifacts, then Android and Windows applications. Execute available device and desktop runtime checks, repair failures and record evidence.

Windows and Android are mandatory. New engine/API modules deliberately have no Apple targets. Existing Apple source stays in place; no Apple compilation or packaging is requested. Linux JVM reuse is best effort.

## Initial build finding

`gradlew tasks --all` initially failed creating Android Compose Preview tasks because unit-test Android resources were disabled. Android unit-test resource inclusion is now configured explicitly. The initial standalone `:plugin-api:compileKotlinDesktop :engine:compileKotlinDesktop` checkpoint succeeded with the reused runtime before further integration changes.
