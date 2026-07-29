package ru.petus.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ru.petus.launcher.core.Json;
import ru.petus.launcher.core.Log;
import ru.petus.launcher.core.Settings;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Microsoft (premium) login via the OAuth 2.0 device code flow — the only flow
 * that works well in a desktop app without shipping a client secret or an
 * embedded browser: we show a code, the user confirms it in a browser, we poll.
 *
 * Chain: MSA → Xbox Live → XSTS → Minecraft services → profile.
 *
 * The Azure application id is a setting (Settings.microsoftClientId): every
 * launcher must register its own public client with the XboxLive.signin scope.
 */
public final class MicrosoftAuth {
    public static final String DEFAULT_SCOPE = "XboxLive.signin offline_access";

    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String MC_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** Details shown to the user while the device code flow is pending. */
    public record DeviceCode(String userCode, String verificationUri, String deviceCode, int interval,
            int expiresIn, String message) {
    }

    public static final class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }

        public AuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static boolean configured() {
        return !Settings.get().microsoftClientId.isBlank();
    }

    private String clientId() {
        String clientId = Settings.get().microsoftClientId;
        if (clientId.isBlank()) {
            throw new AuthException("Не задан Azure Client ID для входа через Microsoft. "
                    + "Укажите его в Настройках → Аккаунты.");
        }
        return clientId;
    }

    // --- low level helpers ----------------------------------------------
    private JsonObject postForm(String url, Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        form.forEach((key, value) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), true);
    }

    private JsonObject postJson(String url, JsonObject payload, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build(), false);
    }

    private JsonObject getJson(String url, String bearer) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(), false);
    }

    private JsonObject send(HttpRequest request, boolean allowErrorBody) {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonObject object = Json.parseObject(response.body());
            if (response.statusCode() / 100 != 2 && !allowErrorBody) {
                throw new AuthException("Microsoft ответил " + response.statusCode() + ": "
                        + Json.string(object, "errorMessage", response.body()));
            }
            return object;
        } catch (IOException error) {
            throw new AuthException("Нет связи с серверами Microsoft: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AuthException("Вход прерван");
        }
    }

    // --- flow ------------------------------------------------------------
    public DeviceCode requestDeviceCode() {
        JsonObject response = postForm(DEVICE_CODE_URL, Map.of(
                "client_id", clientId(),
                "scope", DEFAULT_SCOPE));
        String deviceCode = Json.string(response, "device_code", "");
        if (deviceCode.isEmpty()) {
            throw new AuthException("Microsoft не выдал код устройства: "
                    + Json.string(response, "error_description", "неизвестная ошибка"));
        }
        return new DeviceCode(
                Json.string(response, "user_code", ""),
                Json.string(response, "verification_uri", "https://microsoft.com/link"),
                deviceCode,
                Math.max(1, Json.number(response, "interval", 5)),
                Json.number(response, "expires_in", 900),
                Json.string(response, "message", ""));
    }

    /** Polls until the user confirms the code, then completes the whole chain. */
    public Account completeDeviceCode(DeviceCode code, Consumer<String> progress) {
        long deadline = System.currentTimeMillis() + code.expiresIn() * 1000L;
        int interval = code.interval();
        while (System.currentTimeMillis() < deadline) {
            sleep(interval);
            JsonObject response = postForm(TOKEN_URL, Map.of(
                    "client_id", clientId(),
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code", code.deviceCode()));
            String error = Json.string(response, "error", "");
            if (error.isEmpty()) {
                progress.accept("Подтверждено. Получаем сессию Minecraft…");
                return finishLogin(Json.string(response, "access_token", ""),
                        Json.string(response, "refresh_token", ""), progress);
            }
            switch (error) {
                case "authorization_pending" -> progress.accept("Ждём подтверждения в браузере…");
                case "slow_down" -> interval += 5;
                case "authorization_declined" -> throw new AuthException("Вы отклонили вход в Microsoft");
                case "expired_token" -> throw new AuthException("Код истёк. Попробуйте ещё раз.");
                default -> throw new AuthException("Microsoft: "
                        + Json.string(response, "error_description", error));
            }
        }
        throw new AuthException("Время ожидания входа истекло");
    }

    /** Silent re-login for a stored premium account. */
    public Account refresh(Account account) {
        if (account.msaRefreshToken == null || account.msaRefreshToken.isBlank()) {
            throw new AuthException("Нет refresh-токена Microsoft — войдите в аккаунт заново");
        }
        JsonObject response = postForm(TOKEN_URL, Map.of(
                "client_id", clientId(),
                "grant_type", "refresh_token",
                "refresh_token", account.msaRefreshToken,
                "scope", DEFAULT_SCOPE));
        String error = Json.string(response, "error", "");
        if (!error.isEmpty()) {
            throw new AuthException("Microsoft: " + Json.string(response, "error_description", error));
        }
        Account refreshed = finishLogin(Json.string(response, "access_token", ""),
                Json.string(response, "refresh_token", account.msaRefreshToken), message -> { });
        refreshed.id = account.id;
        return refreshed;
    }

    private Account finishLogin(String msaAccessToken, String msaRefreshToken, Consumer<String> progress) {
        if (msaAccessToken.isBlank()) {
            throw new AuthException("Microsoft не выдал access token");
        }

        progress.accept("Xbox Live…");
        JsonObject xblProperties = new JsonObject();
        xblProperties.addProperty("AuthMethod", "RPS");
        xblProperties.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProperties.addProperty("RpsTicket", "d=" + msaAccessToken);
        JsonObject xblRequest = new JsonObject();
        xblRequest.add("Properties", xblProperties);
        xblRequest.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblRequest.addProperty("TokenType", "JWT");
        JsonObject xbl = postJson(XBL_URL, xblRequest, null);
        String xblToken = Json.string(xbl, "Token", "");

        progress.accept("XSTS…");
        JsonArray tokens = new JsonArray();
        tokens.add(xblToken);
        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        xstsProperties.add("UserTokens", tokens);
        JsonObject xstsRequest = new JsonObject();
        xstsRequest.add("Properties", xstsProperties);
        xstsRequest.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsRequest.addProperty("TokenType", "JWT");
        JsonObject xsts = postJson(XSTS_URL, xstsRequest, null);
        String xstsToken = Json.string(xsts, "Token", "");
        String userHash = userHash(xsts);

        progress.accept("Minecraft services…");
        JsonObject mcRequest = new JsonObject();
        mcRequest.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JsonObject mc = postJson(MC_LOGIN_URL, mcRequest, null);
        String mcAccessToken = Json.string(mc, "access_token", "");
        long expiresIn = Json.number(mc, "expires_in", 86400);

        JsonObject entitlements = getJson(MC_ENTITLEMENTS_URL, mcAccessToken);
        if (entitlements.has("items") && entitlements.getAsJsonArray("items").isEmpty()) {
            throw new AuthException("На этом аккаунте нет купленного Minecraft: Java Edition");
        }

        progress.accept("Профиль…");
        JsonObject profile = getJson(MC_PROFILE_URL, mcAccessToken);
        String name = Json.string(profile, "name", "");
        String rawUuid = Json.string(profile, "id", "");
        if (name.isBlank() || rawUuid.isBlank()) {
            throw new AuthException("Аккаунт без профиля Minecraft — сначала создайте ник в лаунчере Mojang");
        }

        Account account = new Account();
        account.type = Account.Type.MICROSOFT;
        account.name = name;
        account.uuid = dashed(rawUuid);
        account.accessToken = mcAccessToken;
        account.accessExpiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        account.msaRefreshToken = msaRefreshToken;
        if (profile.has("skins") && !profile.getAsJsonArray("skins").isEmpty()) {
            account.skinUrl = Json.string(profile.getAsJsonArray("skins").get(0).getAsJsonObject(), "url", null);
        }
        Log.info("Microsoft login complete for " + name);
        return account;
    }

    private static String userHash(JsonObject xsts) {
        if (xsts.has("DisplayClaims")) {
            JsonObject claims = xsts.getAsJsonObject("DisplayClaims");
            if (claims.has("xui") && !claims.getAsJsonArray("xui").isEmpty()) {
                return Json.string(claims.getAsJsonArray("xui").get(0).getAsJsonObject(), "uhs", "");
            }
        }
        throw new AuthException("Xbox Live не вернул user hash (возможно, детский аккаунт без разрешений)");
    }

    private static String dashed(String raw) {
        if (raw.contains("-")) {
            return raw;
        }
        return raw.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5");
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuthException("Вход прерван");
        }
    }
}
