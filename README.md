# MyLauncher

MyLauncher is a Windows launcher project targeting Java 21 and JavaFX. The
current build provides the launcher window and persistent settings, plus a
separate Windows setup application that downloads and verifies launcher files
from GitHub Releases.

> **Current status:** this is an early project release, not yet a playable
> Minecraft launcher. Microsoft sign-in, Minecraft 1.21.1 and NeoForge 21.1.251
> installation, bundled game Java, game launch, mod management, and in-app
> updates have not been implemented.

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
