package ru.petus.launcher.api;

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
import java.util.List;
import java.util.Map;

/** Typed HTTP client for launcher.petus.ru. */
public final class ApiClient {
    private static final ApiClient INSTANCE = new ApiClient();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ApiClient() {
    }

    public static ApiClient get() {
        return INSTANCE;
    }

    public HttpClient http() {
        return http;
    }

    public String base() {
        return Settings.get().apiBaseUrl;
    }

    // --- low level -------------------------------------------------------
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "PetusLauncher/" + ru.petus.launcher.Bootstrap.version());
    }

    private <T> T send(HttpRequest request, Class<T> type) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw toError(response);
            }
            return Json.GSON.fromJson(response.body(), type);
        } catch (IOException error) {
            throw new Models.ApiError(0, "network", "Нет связи с launcher.petus.ru: " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Models.ApiError(0, "interrupted", "Запрос прерван");
        }
    }

    private Models.ApiError toError(HttpResponse<String> response) {
        String code = "http_" + response.statusCode();
        String message = response.body();
        try {
            JsonObject object = Json.parseObject(response.body());
            code = Json.string(object, "error", code);
            message = Json.string(object, "note", Json.string(object, "message", code));
        } catch (RuntimeException ignored) {
            // Non-JSON error bodies are used verbatim.
        }
        Log.warn("API " + response.uri() + " -> " + response.statusCode() + " " + code);
        return new Models.ApiError(response.statusCode(), code, humanize(code, message));
    }

    private static String humanize(String code, String fallback) {
        return switch (code) {
            case "invalid_token" -> "Сессия истекла — войдите в PetusID заново";
            case "invalid_grant" -> "Сохранённый вход больше не действителен — войдите заново";
            case "authorization_required" -> "Для этого сервера нужно подтвердить вход через PetusID";
            case "server_unavailable" -> "Сервер временно недоступен";
            case "app_not_configured" -> "Сервер авторизации не настроен. Напишите администрации";
            default -> fallback == null || fallback.isBlank() ? code : fallback;
        };
    }

    private static String json(Map<String, Object> body) {
        return Json.GSON.toJson(body);
    }

    // --- public metadata -------------------------------------------------
    public Models.ApiConfig config() {
        return send(request("/api/config").GET().build(), Models.ApiConfig.class);
    }

    public List<Models.ServerEntry> servers() {
        Models.ServerList list = send(request("/api/servers").GET().build(), Models.ServerList.class);
        return list == null || list.servers == null ? List.of() : list.servers;
    }

    public Models.LauncherRelease launcherVersion() {
        return send(request("/api/launcher/version").GET().build(), Models.LauncherRelease.class);
    }

    public Models.ModResolve resolveMod(String slug, String gameVersion, String loader) {
        String query = "?slug=" + URLEncoder.encode(slug, StandardCharsets.UTF_8)
                + "&gameVersion=" + URLEncoder.encode(gameVersion, StandardCharsets.UTF_8)
                + "&loader=" + URLEncoder.encode(loader, StandardCharsets.UTF_8);
        return send(request("/api/mods/resolve" + query).GET().build(), Models.ModResolve.class);
    }

    // --- sessions --------------------------------------------------------
    public Models.Handoff claim(String handoff) {
        return send(request("/oauth/claim")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("handoff", handoff)), StandardCharsets.UTF_8))
                .build(), Models.Handoff.class);
    }

    public Models.Handoff me(String accessToken) {
        return send(request("/api/session/me").header("Authorization", "Bearer " + accessToken).GET().build(),
                Models.Handoff.class);
    }

    public Models.Handoff refresh(String refreshToken) {
        return send(request("/api/session/refresh")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("refreshToken", refreshToken)),
                        StandardCharsets.UTF_8))
                .build(), Models.Handoff.class);
    }

    public void logout(String accessToken) {
        try {
            send(request("/api/session/logout")
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(), JsonObject.class);
        } catch (RuntimeException error) {
            Log.warn("Logout request failed (ignored): " + error.getMessage());
        }
    }

    // --- game ------------------------------------------------------------
    public Models.JoinTicket ticket(String accessToken, String serverId, String accountName, String accountUuid,
            boolean premium) {
        Map<String, Object> body = Map.of(
                "serverId", serverId,
                "accountName", accountName == null ? "" : accountName,
                "accountUuid", accountUuid == null ? "" : accountUuid,
                "premium", premium);
        return send(request("/api/game/ticket")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8))
                .build(), Models.JoinTicket.class);
    }

    /** URL the launcher opens for a PetusID login. */
    public String startUrl(String app, int loopbackPort, String sessionId, String serverId, boolean embedded) {
        StringBuilder url = new StringBuilder(base()).append("/oauth/").append(app).append("/start?port=")
                .append(loopbackPort);
        if (embedded) {
            url.append("&mode=embedded");
        }
        if (sessionId != null && !sessionId.isBlank()) {
            url.append("&session=").append(URLEncoder.encode(sessionId, StandardCharsets.UTF_8));
        }
        if (serverId != null && !serverId.isBlank()) {
            url.append("&serverId=").append(URLEncoder.encode(serverId, StandardCharsets.UTF_8));
        }
        return url.toString();
    }
}
