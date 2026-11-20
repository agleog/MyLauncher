package com.mylauncher.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

final class ShortcutManager {
    void create(Path launcherExecutable) throws IOException, InterruptedException {
        String target = Base64.getEncoder().encodeToString(launcherExecutable.toAbsolutePath().toString()
                .getBytes(StandardCharsets.UTF_8));
        String script = ""
                + "$ErrorActionPreference='Stop';"
                + "$target=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + target + "'));"
                + "$desktop=[Environment]::GetFolderPath('Desktop');"
                + "$programs=[Environment]::GetFolderPath('Programs');"
                + "$start=Join-Path $programs 'MyLauncher';"
                + "[IO.Directory]::CreateDirectory($start) | Out-Null;"
                + "$shell=New-Object -ComObject WScript.Shell;"
                + "foreach($path in @((Join-Path $desktop 'MyLauncher.lnk'),(Join-Path $start 'MyLauncher.lnk'))){"
                + "$link=$shell.CreateShortcut($path);$link.TargetPath=$target;"
                + "$link.WorkingDirectory=[IO.Path]::GetDirectoryName($target);"
                + "$link.Description='MyLauncher';$link.Save()}";
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedScript)
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(Duration.ofSeconds(30).toSeconds(), TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("Windows не завершил создание ярлыков за отведённое время.");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            throw new IOException("Не удалось создать ярлыки MyLauncher: "
                    + (output.isBlank() ? "PowerShell завершился с кодом " + process.exitValue() : output));
        }
    }
}
