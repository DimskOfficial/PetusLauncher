package ru.petus.auth.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the {@code jti} of every accepted ticket so the same ticket cannot be
 * replayed by a second connection. Bounded in both size and time, so a hostile
 * flood cannot grow it without limit.
 */
public final class ReplayGuard {
    private final Map<String, Long> seen = new LinkedHashMap<>();
    private final int maxEntries;
    private final long ttlMillis;

    public ReplayGuard(int maxEntries, long ttlSeconds) {
        this.maxEntries = Math.max(64, maxEntries);
        this.ttlMillis = Math.max(1_000L, ttlSeconds * 1_000L);
    }

    /** @return true when the id was unused, false when it is a replay. */
    public synchronized boolean claim(String jti) {
        if (jti == null || jti.isBlank()) {
            // A ticket without an id cannot be tracked; treat it as single-use anyway.
            return true;
        }
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(entry -> entry.getValue() < now);
        if (seen.containsKey(jti)) {
            return false;
        }
        while (seen.size() >= maxEntries) {
            var iterator = seen.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        seen.put(jti, now + ttlMillis);
        return true;
    }

    public synchronized void forget(String jti) {
        if (jti != null) {
            seen.remove(jti);
        }
    }

    public synchronized int size() {
        return seen.size();
    }
}
