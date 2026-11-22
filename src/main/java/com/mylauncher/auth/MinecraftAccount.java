package com.mylauncher.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MinecraftAccount(
        String uuid,
        String username,
        @JsonProperty("refreshToken") String sessionToken,
        String skinUrl,
        String clientToken,
        AuthProvider authProvider
) {
    public MinecraftAccount {
        if (uuid == null || !uuid.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("Профиль Minecraft вернул некорректный UUID.");
        }
        if (username == null || !username.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Профиль Minecraft вернул некорректный никнейм.");
        }
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new IllegalArgumentException("Сервис авторизации не вернул токен аккаунта.");
        }
        if (authProvider == null) {
            authProvider = AuthProvider.UNSUPPORTED;
        }
        if (clientToken == null) {
            clientToken = "";
        }
        if (authProvider == AuthProvider.ELY_BY && clientToken.isBlank()) {
            throw new IllegalArgumentException("Ely.by не получил идентификатор клиента.");
        }
        if (skinUrl == null) {
            skinUrl = "";
        }
        if (!skinUrl.isBlank()) {
            java.net.URI skinUri;
            try {
                skinUri = java.net.URI.create(skinUrl);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Профиль Minecraft вернул некорректный адрес скина.", exception);
            }
            if (!"https".equalsIgnoreCase(skinUri.getScheme())
                    || !"textures.minecraft.net".equalsIgnoreCase(skinUri.getHost())
                    || skinUri.getUserInfo() != null || skinUri.getPort() != -1 && skinUri.getPort() != 443) {
                throw new IllegalArgumentException("Профиль Minecraft вернул небезопасный адрес скина.");
            }
        }
        uuid = uuid.toLowerCase(java.util.Locale.ROOT);
    }

    public MinecraftAccount(String uuid, String username, String sessionToken, String skinUrl) {
        this(uuid, username, sessionToken, skinUrl, "", AuthProvider.UNSUPPORTED);
    }

    @Override
    public String toString() {
        return username;
    }
}
