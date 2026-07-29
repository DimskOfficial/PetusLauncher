package ru.petus.launcher.game;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Offline-mode UUID derivation, identical to vanilla Minecraft:
 * UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8)).
 *
 * Our servers rely on this so a launcher login and a direct offline join map to
 * the same player entry.
 */
public final class OfflineUuid {
    private OfflineUuid() {
    }

    public static String of(String name) {
        return uuid("OfflinePlayer:" + name).toString();
    }

    public static UUID uuid(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(seed.getBytes(StandardCharsets.UTF_8));
            digest[6] &= 0x0f;
            digest[6] |= 0x30; // version 3
            digest[8] &= 0x3f;
            digest[8] |= (byte) 0x80; // IETF variant
            long most = 0;
            long least = 0;
            for (int index = 0; index < 8; index++) {
                most = (most << 8) | (digest[index] & 0xff);
            }
            for (int index = 8; index < 16; index++) {
                least = (least << 8) | (digest[index] & 0xff);
            }
            return new UUID(most, least);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MD5 is required by the JDK spec", impossible);
        }
    }

    /** UUID without dashes — the form Minecraft passes on the command line. */
    public static String compact(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "");
    }
}
