package ru.petus.auth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import ru.petus.auth.common.Buf;
import ru.petus.auth.common.Handshakes;
import ru.petus.auth.common.PetusChannels;
import ru.petus.auth.common.PetusConfig;
import ru.petus.auth.common.TicketVerifier;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Proxy side of the Petus launcher gate.
 *
 * <p>Right after a player lands on a backend server it is challenged; petus-connect
 * answers with the one-time PetusID ticket. A valid ticket means «this player came
 * from PetusLauncher», which is what unlocks the passwordless flow (LimboAuth is
 * told the account needs no password, Sonar is told the address is trusted). An
 * invalid or missing answer is a kick when the server runs in {@code require} mode.
 */
@Plugin(
        id = "petus-auth",
        name = "Petus Auth",
        version = "3.0.0",
        description = "Вход на серверы Petus только через PetusLauncher и PetusID.",
        authors = {"Petus"},
        dependencies = {
                @Dependency(id = "limboauth", optional = true),
                @Dependency(id = "limboapi", optional = true),
                @Dependency(id = "sonar", optional = true),
                @Dependency(id = "floodgate", optional = true)
        }
)
public final class PetusAuthVelocity {
    private static final MinecraftChannelIdentifier CHALLENGE =
            MinecraftChannelIdentifier.create(PetusChannels.NAMESPACE, "challenge");
    private static final MinecraftChannelIdentifier TICKET =
            MinecraftChannelIdentifier.create(PetusChannels.NAMESPACE, "ticket");
    private static final MinecraftChannelIdentifier RESULT =
            MinecraftChannelIdentifier.create(PetusChannels.NAMESPACE, "result");

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Handshakes handshakes = new Handshakes();

    private PetusConfig config = new PetusConfig();
    private TicketVerifier verifier = new TicketVerifier(config);
    private Bridges bridges;

    @Inject
    public PetusAuthVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        reload();
        server.getChannelRegistrar().register(CHALLENGE, TICKET, RESULT);
        server.getCommandManager().register("petusauth", new PetusCommand(), "petus");
        server.getScheduler().buildTask(this, this::sweepTimeouts)
                .repeat(Duration.ofSeconds(1))
                .schedule();
        logger.info("Petus Auth ready: server={} mode={} limboauth={} sonar={}",
                config.serverId, config.mode, bridges.limboAuthPresent(), bridges.sonarPresent());
    }

    private void reload() {
        try {
            config = PetusConfig.load(dataDirectory.resolve("config.json"));
        } catch (Exception error) {
            logger.error("Could not read config.json, keeping the previous settings", error);
            return;
        }
        verifier = new TicketVerifier(config);
        bridges = new Bridges(server, logger, config);
    }

    /**
     * The challenge is sent once the player actually reached a backend server: at
     * that point the client is guaranteed to be in the play phase, so the message
     * cannot race with LimboAuth's virtual server.
     */
    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        if (!config.enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (handshakes.isAuthenticated(player.getUniqueId()) || isExempt(player)) {
            return;
        }
        var pending = handshakes.start(player.getUniqueId(), config.handshake.timeoutSeconds);
        server.getScheduler().buildTask(this, () -> sendChallenge(player, pending.nonce()))
                .delay(Math.max(0, config.handshake.delayMillis), TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void sendChallenge(Player player, String nonce) {
        if (!player.isActive()) {
            return;
        }
        player.sendPluginMessage(CHALLENGE,
                Buf.write(nonce, config.serverId, config.handshake.minClientProtocol));
        if (config.logging.logHandshakes) {
            logger.info("Challenged {} for a Petus ticket", player.getUsername());
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals(TICKET.getId())) {
            return;
        }
        // Never let a client-crafted petus: message reach the backend servers.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof Player player) || !config.enabled) {
            return;
        }

        String ticket;
        String nonce;
        String modVersion;
        try {
            Buf buf = new Buf(event.getData());
            ticket = buf.readString(4096);
            nonce = buf.readString(128);
            modVersion = buf.readString(32);
        } catch (RuntimeException error) {
            deny(player, config.messages.kickInvalidTicket, "malformed-payload");
            return;
        }

        var pending = handshakes.pending(player.getUniqueId()).orElse(null);
        if (pending == null) {
            // Unsolicited answer: ignore it instead of trusting it.
            return;
        }
        if (!pending.nonce().equals(nonce)) {
            deny(player, config.messages.kickInvalidTicket, "nonce-mismatch");
            return;
        }

        TicketVerifier.Result result = verifier.verify(ticket, player.getUsername(), player.getUniqueId());
        if (!result.accepted()) {
            if (config.logging.logFailures) {
                logger.warn("Rejected {}: {}{}", player.getUsername(), result.reason(),
                        config.logging.debugTickets ? " ticket=" + preview(ticket) : "");
            }
            player.sendPluginMessage(RESULT, Buf.write(false, result.reason()));
            deny(player, kickMessageFor(result.reason()), result.reason());
            return;
        }

        handshakes.accept(player.getUniqueId(), player.getUsername(), result.claims());
        player.sendPluginMessage(RESULT, Buf.write(true, "ok"));
        bridges.markLimboAuthPremium(player);
        bridges.markSonarVerified(player);
        if (config.logging.logHandshakes) {
            logger.info("{} authenticated through PetusLauncher (mod {}, account {})",
                    player.getUsername(), modVersion, result.claims().displayName());
        }
        if (!config.messages.welcome.isBlank()) {
            player.sendMessage(render(config.messages.welcome.replace("<name>", player.getUsername())));
        }
        if (!config.messages.authSkipped.isBlank() && bridges.limboAuthPresent()) {
            player.sendMessage(render(config.messages.authSkipped));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        handshakes.forget(event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
    }

    private void sweepTimeouts() {
        if (!config.enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        handshakes.expired(now).forEach((uuid, pending) -> {
            server.getPlayer(uuid).ifPresentOrElse(player -> {
                if (pending.attempts() < Math.max(1, config.handshake.attempts)) {
                    handshakes.retry(uuid);
                    sendChallenge(player, pending.nonce());
                    return;
                }
                handshakes.forget(uuid, player.getUsername());
                if (config.requireLauncher() && config.handshake.kickOnTimeout && !isExempt(player)) {
                    String message = config.handshake.explainMissingMod
                            ? config.messages.kickMissingMod
                            : config.messages.kickTimeout;
                    deny(player, message, "handshake-timeout");
                }
            }, () -> handshakes.forget(uuid, null));
        });
    }

    private boolean isExempt(Player player) {
        var bypass = config.bypass;
        for (String permission : bypass.permissions) {
            if (!permission.isBlank() && player.hasPermission(permission)) {
                return true;
            }
        }
        String name = player.getUsername().toLowerCase(Locale.ROOT);
        if (bypass.names.stream().anyMatch(entry -> entry.toLowerCase(Locale.ROOT).equals(name))) {
            return true;
        }
        String uuid = player.getUniqueId().toString();
        if (bypass.uuids.stream().anyMatch(entry -> entry.equalsIgnoreCase(uuid))) {
            return true;
        }
        String address = player.getRemoteAddress().getAddress().getHostAddress();
        return bypass.addresses.stream().anyMatch(entry -> entry.equals(address));
    }

    private void deny(Player player, String message, String reason) {
        if (!config.requireLauncher() && !"malformed-payload".equals(reason)) {
            // In optional mode a bad ticket simply means «no launcher perks».
            return;
        }
        if (!config.handshake.kickOnInvalidTicket) {
            return;
        }
        player.disconnect(render(message));
    }

    private String kickMessageFor(String reason) {
        return switch (reason) {
            case "expired-ticket" -> config.messages.kickExpiredTicket;
            case "name-mismatch", "uuid-mismatch" -> config.messages.kickNameMismatch;
            case "no-ticket" -> config.messages.kickMissingMod;
            default -> config.messages.kickInvalidTicket;
        };
    }

    private Component render(String message) {
        return mini.deserialize(config.messages.prefix + message);
    }

    private static String preview(String ticket) {
        return ticket.length() <= 24 ? ticket : ticket.substring(0, 24) + "…";
    }

    /** {@code /petusauth reload|status|whois <name>} */
    private final class PetusCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            var source = invocation.source();
            String[] args = invocation.arguments();
            if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                source.sendMessage(render("<white>Сервер:</white> " + config.serverId
                        + " <dark_gray>|</dark_gray> <white>режим:</white> " + config.mode
                        + " <dark_gray>|</dark_gray> <white>вошли через лаунчер:</white> "
                        + handshakes.authenticatedCount()
                        + " <dark_gray>|</dark_gray> <white>ждём ответа:</white> " + handshakes.pendingCount()));
                return;
            }
            if (args[0].equalsIgnoreCase("reload")) {
                if (!source.hasPermission("petus.auth.reload")) {
                    source.sendMessage(render(config.messages.noPermission));
                    return;
                }
                reload();
                source.sendMessage(render(config.messages.reloaded));
                return;
            }
            if (args[0].equalsIgnoreCase("whois") && args.length > 1) {
                handshakes.authenticated(args[1]).ifPresentOrElse(entry -> source.sendMessage(render(
                        "<white>" + entry.name() + "</white> <gray>→</gray> PetusID <white>"
                                + entry.claims().displayName() + "</white> <dark_gray>("
                                + entry.claims().subject() + ")</dark_gray>")),
                        () -> source.sendMessage(render("<gray>Не найден среди игроков лаунчера.</gray>")));
                return;
            }
            source.sendMessage(render("<gray>/petusauth status | reload | whois <name></gray>"));
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return true;
        }
    }

    UUID selfCheck() {
        // Kept for tests: proves the class initialises without a proxy instance.
        return UUID.randomUUID();
    }
}
