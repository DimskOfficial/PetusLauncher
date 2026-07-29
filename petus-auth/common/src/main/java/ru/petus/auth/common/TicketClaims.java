package ru.petus.auth.common;

import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.UUID;

/**
 * Claims of a PetusID join ticket as minted by petus-launcher-api:
 * {@code { typ:"join", sub, sid, srv, name, uuid, prem, mcName?, mcUuid?, jti, exp }}.
 */
public record TicketClaims(
        String type,
        String subject,
        String sessionId,
        String serverId,
        String accountName,
        String accountUuid,
        boolean premium,
        String minecraftName,
        String minecraftUuid,
        String jti,
        long expiresAt
) {
    public static TicketClaims from(JsonObject json) {
        return new TicketClaims(
                string(json, "typ"),
                string(json, "sub"),
                string(json, "sid"),
                string(json, "srv"),
                string(json, "name"),
                string(json, "uuid"),
                json.has("prem") && !json.get("prem").isJsonNull() && json.get("prem").getAsBoolean(),
                string(json, "mcName"),
                string(json, "mcUuid"),
                string(json, "jti"),
                json.has("exp") && !json.get("exp").isJsonNull() ? json.get("exp").getAsLong() : 0L
        );
    }

    public boolean isJoinTicket() {
        return "join".equalsIgnoreCase(type == null ? "" : type);
    }

    public Optional<UUID> uuid() {
        return parseUuid(accountUuid);
    }

    public Optional<UUID> premiumUuid() {
        return parseUuid(minecraftUuid);
    }

    public String displayName() {
        if (minecraftName != null && !minecraftName.isBlank()) {
            return minecraftName;
        }
        return accountName == null ? "" : accountName;
    }

    private static Optional<UUID> parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        try {
            if (value.length() == 32) {
                value = value.substring(0, 8) + '-' + value.substring(8, 12) + '-'
                        + value.substring(12, 16) + '-' + value.substring(16, 20) + '-' + value.substring(20);
            }
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static String string(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive()) {
            return json.get(key).getAsString();
        }
        return null;
    }
}
