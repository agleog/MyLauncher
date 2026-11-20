# MyLauncher

MyLauncher is a Windows launcher project targeting Java 21 and JavaFX. The
current build provides the launcher window and persistent settings, plus a
separate Windows setup application that downloads and verifies launcher files
from GitHub Releases.

> **Current status:** this is an early project release, not yet a playable
> Minecraft launcher. Microsoft sign-in, Minecraft 1.21.1 and NeoForge 21.1.251
> installation, bundled game Java, game launch, and mod management have not
> been implemented. Launcher updates are checked through GitHub Releases and
> installed from a verified archive.

## Run from source

Install Eclipse Temurin JDK 21, then run in PowerShell:

```powershell
.\gradlew.bat run
```

Run the tests with:

```powershell
.\gradlew.bat test :installer:test
```

## Windows release

Push a tag in `vMAJOR.MINOR.PATCH` format, such as `v0.1.0`. GitHub Actions
tests and packages the launcher and setup EXE, then publishes them as assets
on the GitHub Release:

- `MyLauncher-windows-x64.zip`
- `MyLauncher-windows-x64.zip.sha256`
- `MyLauncherSetup.exe`

The setup app installs the launcher files from the latest public release. The
launcher archive contains its packaged Java runtime; the setup app does not
require Java to be installed separately.

When MyLauncher starts, it checks the latest public GitHub Release. If its
`vMAJOR.MINOR.PATCH` version is newer, the home page shows **ОБНОВИТЬ**. The
button downloads the release archive, verifies SHA-256, replaces only the
launcher application files, and restarts the app. Game data is not touched.
The first release with this feature is `v0.2.0`; older installed builds need
to be upgraded once with `MyLauncherSetup.exe` before in-app updates can work.
