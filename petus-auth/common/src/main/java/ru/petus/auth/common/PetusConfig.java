package ru.petus.auth.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration of petus-auth, shared by the Velocity and Paper/Folia plugins.
 *
 * <p>Stored as JSON next to the plugin so that both platforms read exactly the
 * same file format, and so an operator can template it from Dokploy secrets
 * without dragging a YAML dependency into the proxy.
 */
public final class PetusConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Master switch — false disables every check and every kick. */
    public boolean enabled = true;

    /** Server id as registered in the launcher API: petusmc, petuscreate, ... */
    public String serverId = "petusmc";

    /**
     * require  — a valid launcher ticket is mandatory, everyone else is kicked.
     * optional — tickets are honoured (and skip auth), plain players may still join.
     */
    public String mode = "optional";

    public Handshake handshake = new Handshake();
    public Verification verification = new Verification();
    public Identity identity = new Identity();
    public Bypass bypass = new Bypass();
    public Integrations integrations = new Integrations();
    public Messages messages = new Messages();
    public Logging logging = new Logging();

    public boolean requireLauncher() {
        return "require".equalsIgnoreCase(mode);
    }

    public static final class Handshake {
        /** How long the player has to answer the challenge before being judged. */
        public int timeoutSeconds = 12;
        /** Small delay before the challenge, so resource-pack style clients settle first. */
        public int delayMillis = 250;
        /** How many challenges to send before giving up. */
        public int attempts = 2;
        public boolean kickOnTimeout = true;
        public boolean kickOnInvalidTicket = true;
        /** Minimum petus-connect protocol accepted from the client. */
        public int minClientProtocol = 3;
        /** Tell the player which mod is missing instead of a bare kick. */
        public boolean explainMissingMod = true;
    }

    public static final class Verification {
        public Offline offline = new Offline();
        public Remote remote = new Remote();
        /** When true, both the signature and the API must accept the ticket. */
        public boolean requireBoth = false;
        public ReplayProtection replayProtection = new ReplayProtection();
    }

    public static final class Offline {
        public boolean enabled = true;
        /** Same value as TICKET_SECRET in petus-launcher-api. */
        public String secret = "";
        public int allowedClockSkewSeconds = 30;
    }

    public static final class Remote {
        public boolean enabled = false;
        public String apiBaseUrl = "https://launcher.petus.ru";
        /** Same value as SERVICE_SECRET in petus-launcher-api. */
        public String serviceSecret = "";
        public int timeoutSeconds = 5;
        /** Burn the ticket API-side so it cannot be reused elsewhere. */
        public boolean consumeTicket = true;
        /** Let players in when the API is unreachable (handy during API deploys). */
        public boolean failOpen = false;
    }

    public static final class ReplayProtection {
        public boolean enabled = true;
        public int cacheSize = 10_000;
        public int ttlSeconds = 900;
    }

    public static final class Identity {
        /** Kick when the joined nickname differs from the one inside the ticket. */
        public boolean enforceNameMatch = true;
        /** Kick when the joined uuid differs from the one inside the ticket. */
        public boolean enforceUuidMatch = false;
        /** Remember uuid -> PetusID account for other plugins and for /petus whois. */
        public boolean trackAccounts = true;
    }

    public static final class Bypass {
        /** Players with any of these permissions never need a ticket. */
        public List<String> permissions = new ArrayList<>(List.of("petus.auth.bypass"));
        public List<String> names = new ArrayList<>();
        public List<String> uuids = new ArrayList<>();
        /** Useful for a local admin console or a stress-test host. */
        public List<String> addresses = new ArrayList<>(List.of("127.0.0.1"));
    }

    public static final class Integrations {
        public LimboAuth limboauth = new LimboAuth();
        public Sonar sonar = new Sonar();
        public Floodgate floodgate = new Floodgate();
    }

    public static final class LimboAuth {
        /** Skip the login/register limbo for launcher-authenticated players. */
        public boolean bypass = true;
        /** Mark the account premium in LimboAuth's database so it stays skipped. */
        public boolean markPremium = true;
        /** Create the account row if it does not exist yet. */
        public boolean createMissingAccount = true;
        public boolean logSkips = true;
    }

    public static final class Sonar {
        /** Ask Sonar to treat launcher players as already verified. */
        public boolean bypass = true;
        /** Add the address to Sonar's verified cache so reconnects are instant. */
        public boolean addToVerifiedCache = true;
        public boolean logSkips = true;
    }

    public static final class Floodgate {
        /** Bedrock players can never run the mod, so decide explicitly. */
        public boolean allowBedrock = false;
    }

    public static final class Messages {
        public String prefix = "<gradient:#7C5CFF:#B39BFF>Petus</gradient> <dark_gray>│</dark_gray> ";
        public String kickMissingMod =
                "<red>Вход только через PetusLauncher.</red><newline><gray>Скачать: <white>launcher.petus.ru</white></gray>";
        public String kickInvalidTicket =
                "<red>Ключ входа недействителен.</red><newline><gray>Перезапусти лаунчер и войди заново.</gray>";
        public String kickExpiredTicket =
                "<red>Ключ входа истёк.</red><newline><gray>Нажми «Играть» в лаунчере ещё раз.</gray>";
        public String kickTimeout =
                "<red>Лаунчер не ответил вовремя.</red><newline><gray>Попробуй зайти снова.</gray>";
        public String kickNameMismatch =
                "<red>Ник не совпадает с аккаунтом PetusID.</red>";
        public String kickBedrock = "<red>Bedrock-клиенты пока не поддерживаются.</red>";
        public String welcome = "<green>С возвращением, <white><name></white>!</green>";
        public String authSkipped = "<gray>Вход через PetusID — пароль не нужен.</gray>";
        public String reloaded = "<green>Конфигурация petus-auth перезагружена.</green>";
        public String noPermission = "<red>Нет прав.</red>";
    }

    public static final class Logging {
        public boolean logHandshakes = true;
        public boolean logFailures = true;
        /** Print the first characters of a rejected ticket — debugging only. */
        public boolean debugTickets = false;
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    /** Loads the config, writing a fully commented default file when missing. */
    public static PetusConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            PetusConfig fresh = new PetusConfig();
            fresh.save(file);
            return fresh;
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        PetusConfig loaded = GSON.fromJson(raw, PetusConfig.class);
        if (loaded == null) {
            loaded = new PetusConfig();
        }
        loaded.normalize();
        // Re-save so new options added by an update land in the file.
        loaded.save(file);
        return loaded;
    }

    public void save(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, GSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    /** Fills in anything a hand-edited file left out. */
    public void normalize() {
        if (mode == null || mode.isBlank()) {
            mode = "optional";
        }
        if (serverId == null) {
            serverId = "";
        }
        if (handshake == null) {
            handshake = new Handshake();
        }
        if (verification == null) {
            verification = new Verification();
        }
        if (verification.offline == null) {
            verification.offline = new Offline();
        }
        if (verification.remote == null) {
            verification.remote = new Remote();
        }
        if (verification.replayProtection == null) {
            verification.replayProtection = new ReplayProtection();
        }
        if (identity == null) {
            identity = new Identity();
        }
        if (bypass == null) {
            bypass = new Bypass();
        }
        if (bypass.permissions == null) {
            bypass.permissions = new ArrayList<>();
        }
        if (bypass.names == null) {
            bypass.names = new ArrayList<>();
        }
        if (bypass.uuids == null) {
            bypass.uuids = new ArrayList<>();
        }
        if (bypass.addresses == null) {
            bypass.addresses = new ArrayList<>();
        }
        if (integrations == null) {
            integrations = new Integrations();
        }
        if (integrations.limboauth == null) {
            integrations.limboauth = new LimboAuth();
        }
        if (integrations.sonar == null) {
            integrations.sonar = new Sonar();
        }
        if (integrations.floodgate == null) {
            integrations.floodgate = new Floodgate();
        }
        if (messages == null) {
            messages = new Messages();
        }
        if (logging == null) {
            logging = new Logging();
        }
    }
}
