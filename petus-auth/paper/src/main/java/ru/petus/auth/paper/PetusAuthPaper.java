package ru.petus.auth.paper;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import ru.petus.auth.common.Buf;
import ru.petus.auth.common.Handshakes;
import ru.petus.auth.common.PetusChannels;
import ru.petus.auth.common.PetusConfig;
import ru.petus.auth.common.TicketVerifier;

import java.time.Duration;
import java.util.Locale;

/**
 * Paper/Folia side of the Petus launcher gate.
 *
 * <p>Used on PetusCreate, which has no proxy in front of it: the plugin performs the
 * same nonce bound handshake as the Velocity build and kicks anyone who cannot show
 * a valid PetusID ticket. Every scheduled action goes through the global region
 * scheduler, so the plugin is safe on Folia as well.
 */
public final class PetusAuthPaper extends JavaPlugin implements Listener, PluginMessageListener {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Handshakes handshakes = new Handshakes();

    private PetusConfig config = new PetusConfig();
    private TicketVerifier verifier = new TicketVerifier(config);
    private ScheduledTask sweeper;

    @Override
    public void onEnable() {
        reload();
        getServer().getMessenger().registerOutgoingPluginChannel(this, PetusChannels.CHALLENGE);
        getServer().getMessenger().registerOutgoingPluginChannel(this, PetusChannels.RESULT);
        getServer().getMessenger().registerIncomingPluginChannel(this, PetusChannels.TICKET, this);
        getServer().getPluginManager().registerEvents(this, this);
        sweeper = getServer().getGlobalRegionScheduler()
                .runAtFixedRate(this, task -> sweepTimeouts(), 20L, 20L);
        getLogger().info("Petus Auth ready: server=" + config.serverId + " mode=" + config.mode);
    }

    @Override
    public void onDisable() {
        if (sweeper != null) {
            sweeper.cancel();
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    private void reload() {
        try {
            config = PetusConfig.load(getDataFolder().toPath().resolve("config.json"));
        } catch (Exception error) {
            getLogger().severe("Could not read config.json: " + error);
            return;
        }
        verifier = new TicketVerifier(config);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (isExempt(player)) {
            return;
        }
        var pending = handshakes.start(player.getUniqueId(), config.handshake.timeoutSeconds);
        player.getScheduler().runDelayed(this, task -> sendChallenge(player, pending.nonce()), null,
                Math.max(1L, config.handshake.delayMillis / 50L));
    }

    private void sendChallenge(Player player, String nonce) {
        if (!player.isOnline()) {
            return;
        }
        player.sendPluginMessage(this, PetusChannels.CHALLENGE,
                Buf.write(nonce, config.serverId, config.handshake.minClientProtocol));
        if (config.logging.logHandshakes) {
            getLogger().info("Challenged " + player.getName() + " for a Petus ticket");
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (!PetusChannels.TICKET.equals(channel) || !config.enabled) {
            return;
        }
        String ticket;
        String nonce;
        String modVersion;
        try {
            Buf buf = new Buf(message);
            ticket = buf.readString(4096);
            nonce = buf.readString(128);
            modVersion = buf.readString(32);
        } catch (RuntimeException error) {
            kick(player, config.messages.kickInvalidTicket, "malformed-payload");
            return;
        }

        var pending = handshakes.pending(player.getUniqueId()).orElse(null);
        if (pending == null || !pending.nonce().equals(nonce)) {
            kick(player, config.messages.kickInvalidTicket, "nonce-mismatch");
            return;
        }

        TicketVerifier.Result result = verifier.verify(ticket, player.getName(), player.getUniqueId());
        if (!result.accepted()) {
            if (config.logging.logFailures) {
                getLogger().warning("Rejected " + player.getName() + ": " + result.reason());
            }
            player.sendPluginMessage(this, PetusChannels.RESULT, Buf.write(false, result.reason()));
            kick(player, kickMessageFor(result.reason()), result.reason());
            return;
        }

        handshakes.accept(player.getUniqueId(), player.getName(), result.claims());
        player.sendPluginMessage(this, PetusChannels.RESULT, Buf.write(true, "ok"));
        if (config.logging.logHandshakes) {
            getLogger().info(player.getName() + " authenticated through PetusLauncher (mod " + modVersion
                    + ", account " + result.claims().displayName() + ")");
        }
        if (!config.messages.welcome.isBlank()) {
            player.sendMessage(render(config.messages.welcome.replace("<name>", player.getName())));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        handshakes.forget(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    private void sweepTimeouts() {
        if (!config.enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        handshakes.expired(now).forEach((uuid, pending) -> {
            Player player = getServer().getPlayer(uuid);
            if (player == null) {
                handshakes.forget(uuid, null);
                return;
            }
            if (pending.attempts() < Math.max(1, config.handshake.attempts)) {
                handshakes.retry(uuid);
                sendChallenge(player, pending.nonce());
                return;
            }
            handshakes.forget(uuid, player.getName());
            if (config.requireLauncher() && config.handshake.kickOnTimeout && !isExempt(player)) {
                kick(player, config.handshake.explainMissingMod
                        ? config.messages.kickMissingMod
                        : config.messages.kickTimeout, "handshake-timeout");
            }
        });
    }

    private boolean isExempt(Player player) {
        for (String permission : config.bypass.permissions) {
            if (!permission.isBlank() && player.hasPermission(permission)) {
                return true;
            }
        }
        String name = player.getName().toLowerCase(Locale.ROOT);
        if (config.bypass.names.stream().anyMatch(entry -> entry.toLowerCase(Locale.ROOT).equals(name))) {
            return true;
        }
        String uuid = player.getUniqueId().toString();
        if (config.bypass.uuids.stream().anyMatch(entry -> entry.equalsIgnoreCase(uuid))) {
            return true;
        }
        var address = player.getAddress();
        return address != null && address.getAddress() != null
                && config.bypass.addresses.stream()
                .anyMatch(entry -> entry.equals(address.getAddress().getHostAddress()));
    }

    private void kick(Player player, String message, String reason) {
        if (!config.requireLauncher() && !"malformed-payload".equals(reason)) {
            return;
        }
        if (!config.handshake.kickOnInvalidTicket) {
            return;
        }
        Component component = render(message);
        player.getScheduler().run(this, task -> player.kick(component), null);
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

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                            @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(render("<white>Сервер:</white> " + config.serverId
                    + " <dark_gray>|</dark_gray> <white>режим:</white> " + config.mode
                    + " <dark_gray>|</dark_gray> <white>вошли через лаунчер:</white> "
                    + handshakes.authenticatedCount()));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("petus.auth.reload")) {
                sender.sendMessage(render(config.messages.noPermission));
                return true;
            }
            reload();
            sender.sendMessage(render(config.messages.reloaded));
            return true;
        }
        if (args[0].equalsIgnoreCase("whois") && args.length > 1) {
            handshakes.authenticated(args[1]).ifPresentOrElse(
                    entry -> sender.sendMessage(render("<white>" + entry.name()
                            + "</white> <gray>→</gray> PetusID <white>" + entry.claims().displayName()
                            + "</white>")),
                    () -> sender.sendMessage(render("<gray>Не найден среди игроков лаунчера.</gray>")));
            return true;
        }
        sender.sendMessage(render("<gray>/petusauth status | reload | whois <name></gray>"));
        return true;
    }
}
