package ru.petus.launcher.auth;

import ru.petus.launcher.api.ApiClient;
import ru.petus.launcher.api.Models;
import ru.petus.launcher.core.AppDirs;
import ru.petus.launcher.core.Json;
import ru.petus.launcher.core.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The PetusID session of the launcher itself: access token (short lived),
 * refresh token (rotating) and the cached profile. Persisted so the launcher
 * opens straight into the main window on the next start.
 */
public final class SessionStore {
    /** On-disk shape. */
    public static final class Persisted {
        public String sessionId;
        public String accessToken;
        public long accessExpiresAt;
        public String refreshToken;
        public Models.UserInfo user;
        public Map<String, Models.Grant> grants = new LinkedHashMap<>();
    }

    private static final SessionStore INSTANCE = new SessionStore();

    private Persisted state = new Persisted();

    private SessionStore() {
        load();
    }

    public static SessionStore get() {
        return INSTANCE;
    }

    private void load() {
        try {
            if (Files.exists(AppDirs.sessionFile())) {
                Persisted loaded = Json.read(AppDirs.sessionFile(), Persisted.class);
                if (loaded != null) {
                    state = loaded;
                    if (state.grants == null) {
                        state.grants = new LinkedHashMap<>();
                    }
                    Log.info("Restored PetusID session for " + (state.user == null ? "?" : state.user.label()));
                }
            }
        } catch (Exception error) {
            Log.warn("Cannot read session.json: " + error);
            state = new Persisted();
        }
    }

    private void persist() {
        try {
            Json.write(AppDirs.sessionFile(), state);
        } catch (IOException error) {
            Log.error("Cannot save session.json", error);
        }
    }

    public synchronized void adopt(Models.Handoff handoff) {
        state.sessionId = handoff.sessionId;
        state.accessToken = handoff.accessToken;
        state.accessExpiresAt = System.currentTimeMillis()
                + Math.max(60, handoff.accessTokenExpiresIn - 60) * 1000L;
        if (handoff.refreshToken != null && !handoff.refreshToken.isBlank()) {
            state.refreshToken = handoff.refreshToken;
        }
        if (handoff.user != null) {
            state.user = handoff.user;
        }
        state.grants = handoff.grants == null ? new LinkedHashMap<>() : handoff.grants;
        persist();
        Log.info("PetusID session stored for " + (state.user == null ? "?" : state.user.label()));
    }

    public synchronized boolean hasSession() {
        return state.refreshToken != null && !state.refreshToken.isBlank();
    }

    public synchronized String sessionId() {
        return state.sessionId;
    }

    public synchronized Optional<Models.UserInfo> user() {
        return Optional.ofNullable(state.user);
    }

    public synchronized boolean authorizedFor(String serverId) {
        return state.grants != null && state.grants.containsKey(serverId);
    }

    public synchronized void markAuthorized(String serverId, String app) {
        Models.Grant grant = new Models.Grant();
        grant.app = app;
        grant.grantedAt = System.currentTimeMillis();
        state.grants.put(serverId, grant);
        persist();
    }

    /**
     * Returns a usable access token, refreshing it when it is about to expire.
     * Throws when the session is gone and the user has to log in again.
     */
    public synchronized String accessToken() {
        if (!hasSession()) {
            throw new Models.ApiError(401, "invalid_token", "Войдите в PetusID");
        }
        if (state.accessToken == null || System.currentTimeMillis() >= state.accessExpiresAt) {
            Log.info("Refreshing PetusID launcher session");
            Models.Handoff refreshed = ApiClient.get().refresh(state.refreshToken);
            adopt(refreshed);
        }
        return state.accessToken;
    }

    /** Verifies the stored session against the backend on startup. */
    public synchronized boolean validate() {
        if (!hasSession()) {
            return false;
        }
        try {
            adopt(ApiClient.get().refresh(state.refreshToken));
            return true;
        } catch (RuntimeException error) {
            Log.warn("Stored session is no longer valid: " + error.getMessage());
            clear();
            return false;
        }
    }

    public synchronized void logout() {
        try {
            if (state.accessToken != null) {
                ApiClient.get().logout(state.accessToken);
            }
        } finally {
            clear();
        }
    }

    public synchronized void clear() {
        state = new Persisted();
        try {
            Files.deleteIfExists(AppDirs.sessionFile());
        } catch (IOException ignored) {
            persist();
        }
    }
}
