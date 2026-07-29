package ru.petus.auth.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import ru.petus.auth.common.PetusConfig;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Optional integrations with LimboAuth and Sonar.
 *
 * <p>Both plugins are touched purely through reflection: petus-auth must load and
 * work on a proxy that has neither of them, and it must not break when they change
 * their internals. Every failure is logged once and then ignored.
 */
final class Bridges {
    private final ProxyServer server;
    private final Logger logger;
    private final PetusConfig config;
    private boolean limboWarned;
    private boolean sonarWarned;

    Bridges(ProxyServer server, Logger logger, PetusConfig config) {
        this.server = server;
        this.logger = logger;
        this.config = config;
    }

    boolean limboAuthPresent() {
        return server.getPluginManager().getPlugin("limboauth").isPresent();
    }

    boolean sonarPresent() {
        return server.getPluginManager().getPlugin("sonar").isPresent();
    }

    /**
     * Marks the account as premium in LimboAuth so it stops asking for a password.
     * This is what makes «login through the launcher» feel passwordless on PetusMC.
     */
    void markLimboAuthPremium(Player player) {
        if (!config.integrations.limboauth.bypass || !config.integrations.limboauth.markPremium) {
            return;
        }
        if (!limboAuthPresent()) {
            return;
        }
        try {
            Object instance = pluginInstance("limboauth").orElse(null);
            if (instance == null) {
                return;
            }
            Object dao = invokeFirst(instance, "getPlayerDao", "getDao", "getPlayerRepository");
            if (dao == null) {
                warnLimbo("LimboAuth player dao not found");
                return;
            }
            String name = player.getUsername();
            Method queryForId = dao.getClass().getMethod("queryForId", Object.class);
            Object row = queryForId.invoke(dao, name.toLowerCase(java.util.Locale.ROOT));
            if (row == null && config.integrations.limboauth.createMissingAccount) {
                warnLimbo("no LimboAuth row for " + name + ", leaving registration to LimboAuth");
                return;
            }
            if (row == null) {
                return;
            }
            trySet(row, "setHash", "");
            trySet(row, "setTotpToken", "");
            trySet(row, "setPremiumUuid", player.getUniqueId().toString());
            Method update = dao.getClass().getMethod("update", Object.class);
            update.invoke(dao, row);
            if (config.integrations.limboauth.logSkips) {
                logger.info("LimboAuth: {} marked as launcher-authenticated (password not required)", name);
            }
        } catch (Throwable error) {
            warnLimbo("could not update LimboAuth account: " + error);
        }
    }

    /** Asks Sonar to treat the address as already verified, so reconnects are instant. */
    void markSonarVerified(Player player) {
        if (!config.integrations.sonar.bypass || !config.integrations.sonar.addToVerifiedCache) {
            return;
        }
        if (!sonarPresent()) {
            return;
        }
        try {
            Class<?> sonarClass = Class.forName("xyz.jonesdev.sonar.api.Sonar");
            Object sonar = sonarClass.getMethod("get").invoke(null);
            Object controller = invokeFirst(sonar, "getVerifiedPlayerController", "getFallback");
            if (controller == null) {
                warnSonar("Sonar verified player controller not found");
                return;
            }
            String address = player.getRemoteAddress().getAddress().getHostAddress();
            for (Method method : controller.getClass().getMethods()) {
                if (method.getName().equals("add") && method.getParameterCount() == 2) {
                    method.invoke(controller, address, player.getUniqueId());
                    if (config.integrations.sonar.logSkips) {
                        logger.info("Sonar: {} ({}) added to the verified cache", player.getUsername(), address);
                    }
                    return;
                }
            }
            warnSonar("Sonar add(address, uuid) not found");
        } catch (Throwable error) {
            warnSonar("could not talk to Sonar: " + error);
        }
    }

    private Optional<Object> pluginInstance(String id) {
        return server.getPluginManager().getPlugin(id).flatMap(container -> container.getInstance());
    }

    private static Object invokeFirst(Object target, String... methods) {
        for (String name : methods) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (Throwable ignored) {
                // try the next candidate name
            }
        }
        return null;
    }

    private static void trySet(Object target, String setter, String value) {
        try {
            target.getClass().getMethod(setter, String.class).invoke(target, value);
        } catch (Throwable ignored) {
            // the field simply does not exist in this LimboAuth build
        }
    }

    private void warnLimbo(String message) {
        if (!limboWarned) {
            limboWarned = true;
            logger.warn("LimboAuth integration: {}", message);
        }
    }

    private void warnSonar(String message) {
        if (!sonarWarned) {
            sonarWarned = true;
            logger.warn("Sonar integration: {}", message);
        }
    }
}
