# MyLauncher

MyLauncher is a Windows launcher project targeting Java 21 and JavaFX. The
current build provides the launcher window and persistent settings, plus a
separate Windows setup application that downloads and verifies launcher files
from GitHub Releases.

> **Current status:** MyLauncher installs and verifies the official Minecraft
> 1.21.1 files, NeoForge 21.1.251, and Eclipse Temurin Java 21 x64. It supports
> Ely.by sign-in and launching the installed game through the official
> authlib-injector integration. Mod management is not implemented.

## Run from source

Install Eclipse Temurin JDK 21, then run in PowerShell:

```powershell
.\gradlew.bat run
```

This command tests and launches the latest source in this project directory.
It does not start `D:\minecraft\MyLauncher.exe` or another previously
installed copy. The current game data location remains controlled by launcher
settings.

Run the tests with:

```powershell
.\gradlew.bat test :installer:test
```

## Game Java Runtime

Open **Settings → Java Runtime**, save the game folder, and choose **УСТАНОВИТЬ
TEMURIN 21**. MyLauncher obtains the current Windows x64 JRE 21 package from
the official Adoptium API, verifies its SHA-256 checksum, safely extracts it,
and runs `java -version` before installation. The runtime is stored separately
from the launcher in `Game\runtime\temurin-21`. Use **ПРОВЕРИТЬ JAVA** to check
an existing installation or **ОТМЕНИТЬ** to cancel a download.

## Minecraft 1.21.1 files

On the home page choose **УСТАНОВИТЬ ИГРУ**. MyLauncher obtains the 1.21.1
release metadata from Mojang, then downloads the client, Windows libraries, and
asset objects to the configured game folder. Every file is checked against the
SHA-1 and size published in Mojang's manifests. Existing valid files are reused;
missing or damaged files are downloaded again. Downloads run in the background,
show overall progress, transfer speed, and remaining file count, can be
cancelled, and preserve completed files for the next attempt.
When checking an existing installation, the launcher reports how many bytes it
actually downloads; a long SHA-1 verification with `0 MB` downloaded is only a
local disk check.

Minecraft 1.21.1's official asset index contains about 829 MB of assets. The
home-page button can recheck and repair the installed files.

## NeoForge 21.1.251

The **УСТАНОВИТЬ ИГРУ** button performs the complete setup in one operation:
it verifies/downloads Minecraft 1.21.1, installs or verifies Temurin Java 21,
then downloads the pinned official NeoForge installer from NeoForged's Maven
repository, checks its SHA-256, and runs its headless client-install command.
The generated profile and required NeoForge files are checked before the main
button changes to **ИГРАТЬ**. The installation can be cancelled and resumed.

Once Minecraft, NeoForge, and Temurin Java 21 are verified, choose **ИГРАТЬ**.
Before launch the launcher checks and repairs the Minecraft files, refreshes the
Ely.by session, resolves the inherited Minecraft and NeoForge arguments, and
starts the game with the installed Temurin runtime. The launcher downloads
authlib-injector 1.2.8 from its official GitHub release and verifies its pinned
SHA-256 before use. Game output is written to `Game\logs\mylauncher-game.log`.
Open **Консоль** in the sidebar to inspect the recent game log; access-token
arguments are redacted before display, and only the last 256 KB is loaded.

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

## Ely.by account sign-in

Create an Ely.by account on [account.ely.by](https://account.ely.by/), then use
**Добавить Ely.by** on the launcher home page. The launcher sends the login,
password, and optional two-factor code directly to Ely.by's documented
authentication API over HTTPS. Passwords and two-factor codes are not saved.
The Ely.by session token and client ID are encrypted with Windows DPAPI for the
current Windows user in `%APPDATA%\MyLauncher\accounts.dat`; the session is
refreshed through Ely.by when the launcher starts.

Ely.by authentication identifies an Ely.by profile; it does **not** check or
grant a Minecraft Java Edition license. MyLauncher does not claim that a
Minecraft license has been verified or bypass Mojang's license checks.
