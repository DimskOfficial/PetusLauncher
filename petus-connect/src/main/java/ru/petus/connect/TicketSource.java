package ru.petus.connect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the one-time join ticket that PetusLauncher handed to this game process.
 *
 * <p>Three sources are checked, in order of trust:
 * <ol>
 *   <li>the {@code -Dpetus.ticket=} system property,</li>
 *   <li>the {@code PETUS_TICKET} environment variable,</li>
 *   <li>{@code <instance>/petus/ticket.json} written by the launcher.</li>
 * </ol>
 * The file is deleted after a successful read when it is marked single-use, so a
 * ticket never survives the session it was minted for.
 */
public final class TicketSource {
    private TicketSource() {
    }

    public record Ticket(String value, String serverId) {
    }

    public static Optional<Ticket> find() {
        String property = trim(System.getProperty("petus.ticket"));
        String serverId = trim(System.getProperty("petus.server"));
        if (property != null) {
            return Optional.of(new Ticket(property, serverId == null ? "" : serverId));
        }

        String env = trim(System.getenv("PETUS_TICKET"));
        if (env != null) {
            String envServer = trim(System.getenv("PETUS_SERVER_ID"));
            return Optional.of(new Ticket(env, envServer == null ? "" : envServer));
        }

        return fromFile(ticketFile());
    }

    public static Path ticketFile() {
        return FabricLoader.getInstance().getGameDir().resolve("petus").resolve("ticket.json");
    }

    private static Optional<Ticket> fromFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            String value = string(json, "ticket");
            if (value == null) {
                return Optional.empty();
            }
            String server = string(json, "serverId");
            boolean singleUse = !json.has("singleUse") || json.get("singleUse").getAsBoolean();
            if (singleUse) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Keeping the file is harmless: the ticket itself expires in minutes.
                }
            }
            return Optional.of(new Ticket(value, server == null ? "" : server));
        } catch (IOException | RuntimeException error) {
            return Optional.empty();
        }
    }

    private static String string(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive()) {
            return trim(json.get(key).getAsString());
        }
        return null;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).equals("null")) {
            return null;
        }
        return trimmed;
    }
}
