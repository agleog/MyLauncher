package com.mylauncher.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

public final class ElyByAuthService {
    private static final URI AUTHENTICATE_URI = URI.create("https://authserver.ely.by/auth/authenticate");
    private static final URI REFRESH_URI = URI.create("https://authserver.ely.by/auth/refresh");
    private static final int MAX_RESPONSE_CHARS = 1_000_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ElyByAuthService() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public MinecraftAccount signIn(String username, String password, String twoFactorCode, String clientToken)
            throws IOException, InterruptedException {
        validateCredentials(username, password, clientToken);
        String passwordWithTwoFactor = password;
        if (twoFactorCode != null && !twoFactorCode.isBlank()) {
            String code = twoFactorCode.strip();
            if (!code.matches("[0-9]{6,8}")) {
                throw new IllegalArgumentException("Код двухфакторной аутентификации должен содержать 6–8 цифр.");
            }
            passwordWithTwoFactor += ":" + code;
        }

        ObjectNode request = objectMapper.createObjectNode();
        request.put("username", username.strip());
        request.put("password", passwordWithTwoFactor);
        request.put("clientToken", clientToken);
        request.put("requestUser", true);
        return parseAccount(sendJson(AUTHENTICATE_URI, request), clientToken);
    }

    public MinecraftAccount refresh(MinecraftAccount account) throws IOException, InterruptedException {
        if (account.authProvider() != AuthProvider.ELY_BY) {
            throw new IllegalArgumentException("Обновлять можно только аккаунты Ely.by.");
        }
        ObjectNode request = objectMapper.createObjectNode();
        request.put("accessToken", account.sessionToken());
        request.put("clientToken", account.clientToken());
        request.put("requestUser", true);
        MinecraftAccount refreshed = parseAccount(sendJson(REFRESH_URI, request), account.clientToken());
        if (!refreshed.uuid().equals(account.uuid())) {
            throw new IOException("Ely.by вернул другой игровой профиль. Войдите в нужный аккаунт заново.");
        }
        return refreshed;
    }

    static MinecraftAccount parseAccount(JsonNode response, String expectedClientToken) throws IOException {
        String accessToken = requiredText(response, "accessToken");
        String clientToken = response.path("clientToken").asText(expectedClientToken);
        if (!expectedClientToken.equals(clientToken)) {
            throw new IOException("Ely.by вернул неожиданный идентификатор клиента.");
        }
        JsonNode profile = response.path("selectedProfile");
        if (!profile.isObject() || profile.isMissingNode()) {
            throw new IOException("Ответ Ely.by не содержит выбранный игровой профиль.");
        }
        try {
            return new MinecraftAccount(requiredText(profile, "id"), requiredText(profile, "name"),
                    accessToken, "", clientToken, AuthProvider.ELY_BY);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Ely.by вернул некорректный игровой профиль.", exception);
        }
    }

    public static void validateCredentials(String username, String password, String clientToken) {
        if (username == null || username.isBlank() || username.length() > 254
                || username.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Введите логин или адрес электронной почты Ely.by.");
        }
        if (password == null || password.isBlank() || password.length() > 4096) {
            throw new IllegalArgumentException("Введите пароль Ely.by.");
        }
        if (clientToken == null || !clientToken.matches(
                "(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Не удалось создать идентификатор клиента.");
        }
    }

    private JsonNode sendJson(URI uri, JsonNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "MyLauncher")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        var responseFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = responseFuture.get();
        } catch (InterruptedException exception) {
            responseFuture.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof HttpTimeoutException || cause instanceof HttpConnectTimeoutException) {
                throw new IOException("Ely.by не ответил вовремя. Проверьте подключение к интернету и повторите попытку.",
                        cause);
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Не удалось подключиться к Ely.by.", cause);
        }
        if (response.body().length() > MAX_RESPONSE_CHARS) {
            throw new IOException("Ely.by вернул слишком большой ответ.");
        }

        JsonNode responseBody;
        try {
            responseBody = objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IOException("Ely.by вернул некорректный ответ.", exception);
        }
        if (responseBody == null) {
            throw new IOException("Ely.by вернул пустой ответ.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = responseBody.path("errorMessage").asText("");
            if (message.equalsIgnoreCase("Account protected with two factor auth.")) {
                throw new IOException("Для аккаунта включена двухфакторная защита. Введите код 2FA и повторите вход.");
            }
            if (message.equalsIgnoreCase("Invalid credentials. Invalid email or password.")) {
                throw new IOException("Ely.by отклонил логин, пароль или код 2FA. Проверьте данные и повторите вход.");
            }
            throw new IOException(message.isBlank()
                    ? "Не удалось выполнить вход через Ely.by (HTTP " + response.statusCode() + ")."
                    : "Ely.by: " + message);
        }
        return responseBody;
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IOException("Ответ Ely.by не содержит обязательное поле " + field + ".");
        }
        return value;
    }
}
