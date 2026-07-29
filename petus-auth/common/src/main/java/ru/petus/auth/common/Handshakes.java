package ru.petus.auth.common;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks in-flight handshakes and the players that already proved they came from
 * PetusLauncher. Shared by both platforms so the semantics never drift apart.
 */
public final class Handshakes {
    /** A challenge waiting for its answer. */
    public record Pending(String nonce, long deadline, int attempts) {
    }

    /** An accepted launcher session, kept for the duration of the connection. */
    public record Authenticated(UUID uuid, String name, TicketClaims claims, long since) {
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Authenticated> authenticated = new ConcurrentHashMap<>();
    private final Map<String, Authenticated> byName = new ConcurrentHashMap<>();

    public Pending start(UUID player, int timeoutSeconds) {
        Pending value = new Pending(PetusChannels.newNonce(),
                System.currentTimeMillis() + Math.max(1, timeoutSeconds) * 1000L, 1);
        pending.put(player, value);
        return value;
    }

    public Optional<Pending> pending(UUID player) {
        return Optional.ofNullable(pending.get(player));
    }

    public void retry(UUID player) {
        pending.computeIfPresent(player,
                (key, value) -> new Pending(value.nonce(), value.deadline(), value.attempts() + 1));
    }

    public void accept(UUID player, String name, TicketClaims claims) {
        pending.remove(player);
        Authenticated value = new Authenticated(player, name, claims, System.currentTimeMillis());
        authenticated.put(player, value);
        if (name != null) {
            byName.put(name.toLowerCase(java.util.Locale.ROOT), value);
        }
    }

    public void forget(UUID player, String name) {
        pending.remove(player);
        authenticated.remove(player);
        if (name != null) {
            byName.remove(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    public boolean isAuthenticated(UUID player) {
        return authenticated.containsKey(player);
    }

    public Optional<Authenticated> authenticated(UUID player) {
        return Optional.ofNullable(authenticated.get(player));
    }

    public Optional<Authenticated> authenticated(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name.toLowerCase(java.util.Locale.ROOT)));
    }

    public Map<UUID, Pending> expired(long now) {
        Map<UUID, Pending> result = new ConcurrentHashMap<>();
        pending.forEach((player, value) -> {
            if (value.deadline() < now) {
                result.put(player, value);
            }
        });
        return result;
    }

    public int authenticatedCount() {
        return authenticated.size();
    }

    public int pendingCount() {
        return pending.size();
    }
}
