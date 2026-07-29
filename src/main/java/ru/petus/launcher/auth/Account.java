package ru.petus.launcher.auth;

import java.util.UUID;

/**
 * A Minecraft account usable for launching the game.
 *
 *   PETUS     — derived from the PetusID login; offline-mode credentials whose
 *               legitimacy is proven to our servers by the join ticket.
 *   MICROSOFT — real premium account (full online-mode session).
 *   OFFLINE   — a manually typed nickname (only for offline servers/testing).
 */
public final class Account {
    public enum Type { PETUS, MICROSOFT, OFFLINE }

    public String id = UUID.randomUUID().toString();
    public Type type = Type.OFFLINE;
    public String name;
    public String uuid;

    /** Microsoft only — Minecraft services session. */
    public String accessToken;
    public long accessExpiresAt;
    /** Microsoft only — MSA refresh token for silent re-login. */
    public String msaRefreshToken;
    public String skinUrl;

    public static Account petus(String name, String uuid) {
        Account account = new Account();
        account.id = "petus";
        account.type = Type.PETUS;
        account.name = name;
        account.uuid = uuid;
        return account;
    }

    public static Account offline(String name) {
        Account account = new Account();
        account.type = Type.OFFLINE;
        account.name = name;
        account.uuid = ru.petus.launcher.game.OfflineUuid.of(name);
        return account;
    }

    public boolean premium() {
        return type == Type.MICROSOFT;
    }

    public boolean expired() {
        return premium() && System.currentTimeMillis() > accessExpiresAt;
    }

    public String typeLabel() {
        return switch (type) {
            case PETUS -> "PetusID";
            case MICROSOFT -> "Microsoft";
            case OFFLINE -> "Офлайн";
        };
    }

    /** Token handed to Minecraft. Offline accounts get a dummy value. */
    public String gameAccessToken() {
        return premium() && accessToken != null ? accessToken : "0";
    }

    public String userType() {
        return premium() ? "msa" : "legacy";
    }
}
