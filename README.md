# Wave Focus — Windows (EXE) 2.1

Kotlin + Compose Desktop routine tracker with optional Google Cloud sync and
built-in updates.

## v2.1 changes

- **In-app auto-update** (`update/`):
  - `AppVersion.kt` reads the installed version from `version.properties`.
  - `SemVer.kt` is a dependency-free SemVer 2.0 comparator (pre-releases
    included).
  - `UpdateChecker.kt` fetches `updates/version.json` (HTTPS only, cache
    busted), downloads the installer to `%TEMP%\WaveFocus`, verifies the
    advertised byte size and SHA-256, then launches it and exits.
  - `ui/components/AppUpdateBanner.kt` shows a dismissible banner with notes
    and download progress; Settings → *Updates* has the manual check.
  - `tools/publish-update.ps1` writes the manifest (with checksum) and can
    deploy hosting.
- Offline or error states are reported in Settings and never block the app.

## v2 changes

- `util/HijriSync.kt` verifies the Saudi (Umm al-Qura) calendar online and
  feeds a day offset into `util/HijriCalendarHelper.kt`.
- `util/RoutineDay.kt` implements the configurable daily reset time; the main
  settings sheet has a new **Day & Calendar** section
  (`ui/screens/BackupSettingsDialog.kt`).
- Version `2.0.0` for the app and Windows package.

## Build

Requires **JDK 21** (needs `jpackage`) and Gradle 8.6+:

```bash
gradle packageExe            # installer: build/compose/binaries/main/exe/WaveFocus-2.1.0.exe
gradle createDistributable   # portable folder: build/compose/binaries/main/app/WaveFocus
```

`gradle run` starts the app for development.

## Release

1. Edit `src/main/resources/version.properties` (single version source).
2. `gradle packageExe`.
3. Upload the installer to a GitHub release, then run
   `..\website\tools\publish-update.ps1` with the asset URL.
