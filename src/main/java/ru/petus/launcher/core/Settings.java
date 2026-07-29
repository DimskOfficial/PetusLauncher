package ru.petus.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User settings, persisted as settings.json. Every field has a sane default so
 * a missing or partially corrupted file still yields a usable launcher.
 */
public final class Settings {
    /** Backend base URL — overridable for local development. */
    public String apiBaseUrl = "https://launcher.petus.ru";

    // --- Java / JVM ---
    public String javaPath = "";
    public int memoryMb = suggestedMemory();
    public String jvmArgs = "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 "
            + "-XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M -Dfile.encoding=UTF-8";

    // --- game window ---
    public int windowWidth = 1280;
    public int windowHeight = 720;
    public boolean fullscreen = false;

    // --- launcher behaviour ---
    /** keep | minimize | close */
    public String afterLaunch = "minimize";
    public boolean autoUpdateMods = true;
    public boolean autoJoinServer = true;
    public boolean checkLauncherUpdates = true;
    public int downloadThreads = 8;
    public boolean verifyFileHashes = true;
    public boolean showSnapshots = false;
    public String theme = "petus-dark";
    public String language = "ru";

    // --- accounts ---
    public String selectedAccountId = "";
    /** Azure application (public client) used for Microsoft/premium login. */
    public String microsoftClientId = "";

    // --- per server ---
    /** serverId -> chosen Minecraft version. */
    public Map<String, String> serverVersions = new LinkedHashMap<>();
    /** serverId -> (modId -> enabled). Only optional mods are stored. */
    public Map<String, Map<String, Boolean>> serverMods = new LinkedHashMap<>();

    private static Settings instance;

    private static int suggestedMemory() {
        long totalBytes = 4L << 30;
        try {
            com.sun.management.OperatingSystemMXBean bean =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                            .getOperatingSystemMXBean();
            totalBytes = bean.getTotalMemorySize();
        } catch (RuntimeException ignored) {
            // Fall back to 4 GiB when the JVM hides the host memory size.
        }
        long totalMb = totalBytes / (1024 * 1024);
        long suggested = Math.round(totalMb * 0.45);
        return (int) Math.max(2048, Math.min(8192, suggested));
    }

    public static synchronized Settings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Settings load() {
        try {
            if (Files.exists(AppDirs.settingsFile())) {
                Settings loaded = Json.read(AppDirs.settingsFile(), Settings.class);
                if (loaded != null) {
                    loaded.normalize();
                    return loaded;
                }
            }
        } catch (Exception error) {
            Log.warn("settings.json is unreadable, falling back to defaults: " + error);
        }
        return new Settings();
    }

    private void normalize() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://launcher.petus.ru";
        }
        apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
        memoryMb = Math.max(1024, Math.min(32768, memoryMb));
        downloadThreads = Math.max(1, Math.min(32, downloadThreads));
        windowWidth = Math.max(640, windowWidth);
        windowHeight = Math.max(480, windowHeight);
        if (serverVersions == null) {
            serverVersions = new LinkedHashMap<>();
        }
        if (serverMods == null) {
            serverMods = new LinkedHashMap<>();
        }
        if (afterLaunch == null || afterLaunch.isBlank()) {
            afterLaunch = "minimize";
        }
    }

    public synchronized void save() {
        try {
            normalize();
            Json.write(AppDirs.settingsFile(), this);
        } catch (IOException error) {
            Log.error("Cannot save settings", error);
        }
    }

    // --- helpers ---------------------------------------------------------
    public String versionFor(String serverId, String fallback) {
        String chosen = serverVersions.get(serverId);
        return chosen == null || chosen.isBlank() ? fallback : chosen;
    }

    public void setVersionFor(String serverId, String version) {
        serverVersions.put(serverId, version);
        save();
    }

    public boolean modEnabled(String serverId, String modId, boolean fallback) {
        Map<String, Boolean> perServer = serverMods.get(serverId);
        if (perServer == null) {
            return fallback;
        }
        return perServer.getOrDefault(modId, fallback);
    }

    public void setModEnabled(String serverId, String modId, boolean enabled) {
        serverMods.computeIfAbsent(serverId, key -> new LinkedHashMap<>()).put(modId, enabled);
        save();
    }
}
