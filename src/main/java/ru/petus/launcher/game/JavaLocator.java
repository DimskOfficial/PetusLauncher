package ru.petus.launcher.game;

import ru.petus.launcher.core.AppDirs;
import ru.petus.launcher.core.Log;
import ru.petus.launcher.core.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds a Java runtime for the game. Order of preference:
 *   1. the path the user set in the settings
 *   2. common install locations of JDK/JRE 21 (Adoptium, Zulu, Graal, Oracle)
 *   3. the JVM running the launcher (javaw next to java on Windows)
 *
 * The launcher itself needs 21, and so does Minecraft 1.20.5+, so in practice
 * option 3 is almost always right — but a dedicated runtime keeps the game's
 * GC flags away from the UI.
 */
public final class JavaLocator {
    private JavaLocator() {
    }

    public static String javaExecutable(int major) {
        String configured = Settings.get().javaPath;
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        for (Path candidate : candidates()) {
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return current();
    }

    /** Java runtime running the launcher — javaw on Windows to avoid a console. */
    public static String current() {
        Path home = Path.of(System.getProperty("java.home", ""));
        Path binary = home.resolve("bin").resolve(AppDirs.isWindows() ? "javaw.exe" : "java");
        if (Files.isExecutable(binary)) {
            return binary.toString();
        }
        return AppDirs.isWindows() ? "javaw" : "java";
    }

    /** Java installations discovered on this machine, for the settings screen. */
    public static List<Path> candidates() {
        List<Path> roots = new ArrayList<>();
        switch (AppDirs.os()) {
            case WINDOWS -> {
                roots.add(Path.of("C:\\Program Files\\Eclipse Adoptium"));
                roots.add(Path.of("C:\\Program Files\\Java"));
                roots.add(Path.of("C:\\Program Files\\Zulu"));
                roots.add(Path.of("C:\\Program Files\\Microsoft"));
                roots.add(Path.of("C:\\Program Files\\Amazon Corretto"));
            }
            case MACOS -> roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
            case LINUX -> {
                roots.add(Path.of("/usr/lib/jvm"));
                roots.add(Path.of(System.getProperty("user.home", ".") + "/.sdkman/candidates/java"));
            }
        }

        List<Path> found = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                children.forEach(child -> {
                    Path binary = child.resolve(AppDirs.os() == AppDirs.Os.MACOS
                            ? "Contents/Home/bin/java"
                            : "bin/" + (AppDirs.isWindows() ? "javaw.exe" : "java"));
                    if (Files.isExecutable(binary)) {
                        found.add(binary);
                    }
                });
            } catch (IOException error) {
                Log.debug("Cannot scan " + root + ": " + error.getMessage());
            }
        }
        found.sort((left, right) -> right.toString().compareToIgnoreCase(left.toString()));
        return found;
    }
}
