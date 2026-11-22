package com.mylauncher.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SecureAccountStore {
    private final Path storageFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SecureAccountStore() {
        this(defaultStorageFile());
    }

    SecureAccountStore(Path storageFile) {
        this.storageFile = storageFile;
    }

    public StoredAccounts load() throws IOException {
        if (!Files.exists(storageFile)) {
            return new StoredAccounts(List.of(), "");
        }
        requireWindows();
        try {
            byte[] encrypted = Files.readAllBytes(storageFile);
            if (encrypted.length == 0 || encrypted.length > 1024 * 1024) {
                throw new IOException("Зашифрованное хранилище аккаунтов пусто или повреждено.");
            }
            byte[] clear = Crypt32Util.cryptUnprotectData(encrypted);
            try {
                StoredAccounts stored = objectMapper.readValue(clear, StoredAccounts.class);
                return validate(stored);
            } finally {
                java.util.Arrays.fill(clear, (byte) 0);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Не удалось расшифровать аккаунты Windows. Данные не были изменены.", exception);
        }
    }

    public void save(StoredAccounts accounts) throws IOException {
        requireWindows();
        StoredAccounts validated = validate(accounts);
        byte[] clear = objectMapper.writeValueAsBytes(validated);
        byte[] encrypted;
        try {
            encrypted = Crypt32Util.cryptProtectData(clear);
        } catch (RuntimeException exception) {
            throw new IOException("Windows не смогла зашифровать данные аккаунтов.", exception);
        } finally {
            java.util.Arrays.fill(clear, (byte) 0);
        }

        Path parent = storageFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, "accounts-", ".tmp");
        try {
            Files.write(temporaryFile, encrypted);
            try {
                Files.move(temporaryFile, storageFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            java.util.Arrays.fill(encrypted, (byte) 0);
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static StoredAccounts validate(StoredAccounts accounts) throws IOException {
        if (accounts == null || accounts.accounts() == null || accounts.accounts().size() > 32) {
            throw new IOException("Хранилище аккаунтов имеет неверный формат.");
        }
        List<MinecraftAccount> normalizedAccounts = new ArrayList<>();
        for (MinecraftAccount account : accounts.accounts()) {
            try {
                normalizedAccounts.add(new MinecraftAccount(account.uuid(), account.username(),
                        account.sessionToken(), account.skinUrl(), account.clientToken(), account.authProvider()));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IOException("Хранилище аккаунтов содержит некорректный профиль.", exception);
            }
        }
        String activeUuid = accounts.activeUuid() == null ? "" : accounts.activeUuid().toLowerCase(Locale.ROOT);
        if (!activeUuid.isEmpty() && normalizedAccounts.stream().noneMatch(account -> account.uuid().equals(activeUuid))) {
            throw new IOException("Выбранный аккаунт отсутствует в хранилище.");
        }
        return new StoredAccounts(List.copyOf(normalizedAccounts), activeUuid);
    }

    private static void requireWindows() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new IOException("Защищённое хранилище токенов доступно только в Windows.");
        }
    }

    private static Path defaultStorageFile() {
        String appData = System.getenv("APPDATA");
        Path roaming = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Roaming")
                : Path.of(appData);
        return roaming.resolve("MyLauncher").resolve("accounts.dat");
    }

    public record StoredAccounts(List<MinecraftAccount> accounts, String activeUuid) {
    }
}
