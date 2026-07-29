package ru.petus.launcher.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire models for launcher.petus.ru. Plain mutable classes on purpose: Gson
 * fills them by field name, and the shapes mirror src/config.ts in
 * petus-launcher-api one to one.
 */
public final class Models {
    private Models() {
    }

    public static final class ApiConfig {
        public String publicUrl;
        public String petusIdUrl;
        public int sessionAccessTtl;
        public int ticketTtl;
        public LauncherRelease launcher;
    }

    public static final class LauncherRelease {
        public String version;
        public String channel;
        public boolean mandatory;
        public String notes;
        public String windows;
        public String linux;
        public String macos;
        public String jar;

        public String downloadFor(String osName) {
            String direct = switch (osName) {
                case "windows" -> windows;
                case "osx" -> macos;
                default -> linux;
            };
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
            return jar != null && !jar.isBlank() ? jar : null;
        }
    }

    public static final class Link {
        public String label;
        public String url;
    }

    /** A single mod/file of a server modpack. */
    public static final class ModEntry {
        public String id;
        public String name;
        public String summary;
        /** "modrinth" or "direct". */
        public String source;
        public String slug;
        public String url;
        public String sha1;
        public boolean required;
        public boolean enabledByDefault;
        /** core | performance | cheat | utility | visual */
        public String category;
        /** client | both */
        public String side;

        public String categoryLabel() {
            return switch (category == null ? "" : category) {
                case "core" -> "Обязательные";
                case "performance" -> "Производительность";
                case "cheat" -> "Читы";
                case "visual" -> "Графика";
                case "utility" -> "Утилиты";
                default -> "Прочее";
            };
        }
    }

    public static final class ServerEntry {
        public String id;
        public String name;
        public String tagline;
        public String description;
        public String accent;
        public String icon;
        public String banner;
        /** available | maintenance | development */
        public String status;
        public String statusNote;
        public String oauthApp;
        public boolean launcherOnly;
        /** minecraft | geometrydash */
        public String game;
        public String address;
        public Integer port;
        public String coreVersion;
        public String recommendedVersion;
        public List<String> supportedVersions = new ArrayList<>();
        /** vanilla | fabric | forge | neoforge */
        public String loader;
        public String loaderVersion;
        public Integer javaMajor;
        public List<ModEntry> mods = new ArrayList<>();
        public List<String> features = new ArrayList<>();
        public List<Link> links = new ArrayList<>();

        public boolean available() {
            return "available".equals(status);
        }

        public boolean playable() {
            return available() && "minecraft".equals(game);
        }

        public String statusLabel() {
            return switch (status == null ? "" : status) {
                case "available" -> "Доступен";
                case "maintenance" -> "Техработы";
                case "development" -> "В разработке";
                default -> "Неизвестно";
            };
        }

        public String addressWithPort() {
            if (address == null) {
                return "—";
            }
            return port == null || port == 25565 ? address : address + ":" + port;
        }

        public String defaultVersion() {
            if (recommendedVersion != null && !recommendedVersion.isBlank()) {
                return recommendedVersion;
            }
            if (coreVersion != null && !coreVersion.isBlank()) {
                return coreVersion;
            }
            return supportedVersions.isEmpty() ? "1.21.11" : supportedVersions.get(0);
        }
    }

    public static final class UserInfo {
        public String id;
        public String username;
        public String displayName;
        public String email;
        public String avatar;
        public String minecraftName;
        public String minecraftUuid;

        public String label() {
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            return username != null ? username : "Player";
        }
    }

    public static final class Grant {
        public String app;
        public long grantedAt;
    }

    /**
     * Result of POST /oauth/claim. Covers both handoff kinds:
     *   kind = "launcher" — tokens for the launcher session
     *   kind = "server"   — a fresh join ticket for one server
     */
    public static final class Handoff {
        public String kind;
        public String sessionId;
        public String accessToken;
        public int accessTokenExpiresIn;
        public String refreshToken;
        public UserInfo user;
        public Map<String, Grant> grants = new LinkedHashMap<>();

        public String serverId;
        public String app;
        public String ticket;
        public String minecraftName;
        public String minecraftUuid;
        public String offlineUuid;
        public String address;
        public Integer port;

        public boolean isLauncherSession() {
            return "launcher".equals(kind);
        }
    }

    public static final class TicketAccount {
        public String name;
        public String uuid;
        public boolean premium;
    }

    public static final class JoinTicket {
        public String ticket;
        public int expiresIn;
        public String serverId;
        public String address;
        public Integer port;
        public TicketAccount account;
    }

    public static final class ModResolve {
        public String slug;
        public String gameVersion;
        public String loader;
        public String versionId;
        public String versionNumber;
        public String fileName;
        public String url;
        public long size;
        public String sha1;
    }

    public static final class ServerList {
        public List<ServerEntry> servers = new ArrayList<>();
    }

    public static final class ApiError extends RuntimeException {
        public final int status;
        public final String code;

        public ApiError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
