package ru.petus.auth.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Verifies launcher join tickets, offline (HS256 with the shared TICKET_SECRET)
 * and/or online (POST /api/game/verify with the service secret).
 *
 * <p>Offline verification is the default: it costs nothing, works when the API is
 * down, and cannot be tricked because the signature covers every claim. Remote
 * verification is the stricter mode — it also proves the session still exists and
 * lets the API burn the ticket server side.
 */
public final class TicketVerifier {
    /** Outcome of a verification attempt. */
    public record Result(boolean accepted, String reason, TicketClaims claims) {
        public static Result ok(TicketClaims claims) {
            return new Result(true, "ok", claims);
        }

        public static Result deny(String reason) {
            return new Result(false, reason, null);
        }
    }

    private final PetusConfig config;
    private final ReplayGuard replayGuard;
    private final HttpClient http;

    public TicketVerifier(PetusConfig config) {
        this.config = config;
        this.replayGuard = new ReplayGuard(
                config.verification.replayProtection.cacheSize,
                config.verification.replayProtection.ttlSeconds);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, config.verification.remote.timeoutSeconds)))
                .build();
    }

    public ReplayGuard replayGuard() {
        return replayGuard;
    }

    /**
     * @param ticket     raw ticket string sent by petus-connect
     * @param playerName name the player joined with
     * @param playerUuid uuid the player joined with, may be null
     */
    public Result verify(String ticket, String playerName, UUID playerUuid) {
        if (ticket == null || ticket.isBlank()) {
            return Result.deny("no-ticket");
        }

        Result offline = config.verification.offline.enabled
                ? verifyOffline(ticket)
                : Result.ok(null);
        if (!offline.accepted()) {
            return offline;
        }

        Result remote = config.verification.remote.enabled
                ? verifyRemote(ticket)
                : Result.ok(offline.claims());
        if (!remote.accepted()) {
            return remote;
        }

        TicketClaims claims = remote.claims() != null ? remote.claims() : offline.claims();
        if (claims == null) {
            return Result.deny("no-verifier-enabled");
        }

        if (!claims.isJoinTicket()) {
            return Result.deny("wrong-ticket-type");
        }
        if (!config.serverId.isBlank() && claims.serverId() != null
                && !config.serverId.equalsIgnoreCase(claims.serverId())) {
            return Result.deny("wrong-server");
        }
        if (config.identity.enforceNameMatch && playerName != null
                && !playerName.equalsIgnoreCase(claims.displayName())) {
            return Result.deny("name-mismatch");
        }
        if (config.identity.enforceUuidMatch && playerUuid != null) {
            boolean matches = claims.uuid().map(playerUuid::equals).orElse(false)
                    || claims.premiumUuid().map(playerUuid::equals).orElse(false);
            if (!matches) {
                return Result.deny("uuid-mismatch");
            }
        }
        if (config.verification.replayProtection.enabled && !replayGuard.claim(claims.jti())) {
            return Result.deny("replayed-ticket");
        }
        return Result.ok(claims);
    }

    /** HS256 signature + expiry check without touching the network. */
    public Result verifyOffline(String ticket) {
        String secret = config.verification.offline.secret;
        if (secret == null || secret.isBlank()) {
            return Result.deny("offline-secret-missing");
        }
        String[] parts = ticket.split("\\.");
        if (parts.length != 3) {
            return Result.deny("malformed-ticket");
        }
        try {
            JsonObject header = JsonParser.parseString(new String(decode(parts[0]), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String algorithm = header.has("alg") ? header.get("alg").getAsString() : "";
            if (!"HS256".equalsIgnoreCase(algorithm)) {
                return Result.deny("unsupported-alg:" + algorithm.toLowerCase(Locale.ROOT));
            }

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, decode(parts[2]))) {
                return Result.deny("bad-signature");
            }

            JsonObject payload = JsonParser.parseString(new String(decode(parts[1]), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            TicketClaims claims = TicketClaims.from(payload);
            long now = System.currentTimeMillis() / 1000L;
            long skew = Math.max(0, config.verification.offline.allowedClockSkewSeconds);
            if (claims.expiresAt() > 0 && claims.expiresAt() + skew < now) {
                return Result.deny("expired-ticket");
            }
            return Result.ok(claims);
        } catch (Exception error) {
            return Result.deny("verify-error:" + error.getClass().getSimpleName());
        }
    }

    /** Asks the launcher API to validate (and optionally burn) the ticket. */
    public Result verifyRemote(String ticket) {
        PetusConfig.Remote remote = config.verification.remote;
        if (remote.serviceSecret == null || remote.serviceSecret.isBlank()) {
            return remote.failOpen ? Result.ok(null) : Result.deny("service-secret-missing");
        }
        String base = remote.apiBaseUrl.endsWith("/")
                ? remote.apiBaseUrl.substring(0, remote.apiBaseUrl.length() - 1)
                : remote.apiBaseUrl;
        JsonObject body = new JsonObject();
        body.addProperty("ticket", ticket);
        body.addProperty("serverId", config.serverId);
        body.addProperty("consume", remote.consumeTicket);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/game/verify"))
                    .timeout(Duration.ofSeconds(Math.max(1, remote.timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .header("X-Service-Secret", remote.serviceSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return Result.deny("api-status-" + response.statusCode());
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("ok") && !json.get("ok").getAsBoolean()) {
                return Result.deny("api-rejected");
            }
            JsonObject claims = json.has("claims") && json.get("claims").isJsonObject()
                    ? json.getAsJsonObject("claims")
                    : json;
            return Result.ok(TicketClaims.from(claims));
        } catch (Exception error) {
            if (remote.failOpen) {
                return Result.ok(null);
            }
            return Result.deny("api-unreachable");
        }
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_'));
    }
}
