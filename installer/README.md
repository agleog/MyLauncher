# MyLauncherSetup

`MyLauncherSetup` is a separate Java 21 desktop application. Its Windows EXE is
packaged with a private runtime by `jpackage`; installing Java on the user's
computer is not required.

## GitHub Release format

The installer needs the public GitHub repository that will publish launcher
releases. Enter its real `owner/repository` name in the setup window; no
repository name is built into this project.

The release workflow configures the repository name inside the setup EXE, so
the user does not need to type it. Each GitHub Release must include:

- `MyLauncher-windows-x64.zip`, created by `gradlew.bat packageLauncherZip`.
- The archive SHA-256, preferably GitHub's release asset `digest` field, or a
  sibling `MyLauncher-windows-x64.zip.sha256` file containing the 64-digit hash.

The ZIP must contain the `MyLauncher.exe`, `app`, and `runtime` entries created
by `jpackage`. The installer rejects unexpected archive paths and only replaces
those three launcher-owned items. It does not remove game data.

## Build on Windows

Use Eclipse Temurin JDK 21, the checked-in Gradle Wrapper, and WiX Toolset 3.14
for `jpackage`'s Windows EXE bundler. Install WiX with:

```powershell
winget install --id WiXToolset.WiXToolset --source winget
```

Then, from the repository root, build by passing your actual GitHub repository:

```powershell
.\gradlew.bat clean test packageLauncherChecksum :installer:test packageSetupExe '-PreleaseVersion=0.1.0' '-PgithubRepository=OWNER/REPOSITORY'
```

To publish a release, push a version tag such as `v0.1.0`. The GitHub Actions
workflow builds and publishes the launcher archive, checksum, and setup EXE.
The manual build produces:

- `build\distributions\MyLauncher-windows-x64.zip`
- `build\distributions\MyLauncher-windows-x64.zip.sha256`
- `build\distributions\MyLauncherSetup.exe`

`MyLauncherSetup.exe` is the Windows installer for the standalone setup
application. After installing it, open `MyLauncherSetup` to fetch the latest
launcher from your GitHub Release. Publish the release archive and SHA-256 first.
