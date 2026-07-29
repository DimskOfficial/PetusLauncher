package ru.petus.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where the launcher keeps its files. Follows platform conventions so backups
 * and antivirus exclusions behave the way users expect:
 *   Windows  %APPDATA%\PetusLauncher
 *   macOS    ~/Library/Application Support/PetusLauncher
 *   Linux    $XDG_DATA_HOME/PetusLauncher (or ~/.local/share/PetusLauncher)
 */
public final class AppDirs {
    public enum Os { WINDOWS, MACOS, LINUX }

    private static final Os OS = detect();
    private static final Path DATA = resolveData();

    private AppDirs() {
    }

    private static Os detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MACOS;
        }
        return Os.LINUX;
    }

    private static Path resolveData() {
        String override = System.getProperty("petus.dataDir", System.getenv("PETUS_DATA_DIR"));
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        Path home = Path.of(System.getProperty("user.home", "."));
        return switch (OS) {
            case WINDOWS -> {
                String appData = System.getenv("APPDATA");
                yield (appData != null && !appData.isBlank() ? Path.of(appData) : home).resolve("PetusLauncher");
            }
            case MACOS -> home.resolve("Library/Application Support/PetusLauncher");
            case LINUX -> {
                String xdg = System.getenv("XDG_DATA_HOME");
                yield (xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".local/share"))
                        .resolve("PetusLauncher");
            }
        };
    }

    public static Os os() {
        return OS;
    }

    public static boolean isWindows() {
        return OS == Os.WINDOWS;
    }

    public static String osName() {
        return switch (OS) {
            case WINDOWS -> "windows";
            case MACOS -> "osx";
            case LINUX -> "linux";
        };
    }

    public static String arch() {
        String arch = System.getProperty("os.arch", "amd64").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        return arch.contains("64") ? "x64" : "x86";
    }

    public static Path data() {
        return DATA;
    }

    public static Path settingsFile() {
        return DATA.resolve("settings.json");
    }

    public static Path accountsFile() {
        return DATA.resolve("accounts.json");
    }

    public static Path sessionFile() {
        return DATA.resolve("session.json");
    }

    public static Path logs() {
        return DATA.resolve("logs");
    }

    /** Shared Minecraft assets/libraries — one copy for every instance. */
    public static Path shared() {
        return DATA.resolve("shared");
    }

    public static Path instances() {
        return DATA.resolve("instances");
    }

    public static Path instance(String serverId) {
        return instances().resolve(serverId);
    }

    public static Path cache() {
        return DATA.resolve("cache");
    }

    public static void ensureLayout() {
        for (Path path : new Path[] { DATA, logs(), shared(), instances(), cache() }) {
            try {
                Files.createDirectories(path);
            } catch (IOException error) {
                System.err.println("Cannot create " + path + ": " + error);
            }
        }
    }
}
