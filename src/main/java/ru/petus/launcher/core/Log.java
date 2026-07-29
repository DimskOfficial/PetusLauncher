package ru.petus.launcher.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tiny append-only logger. Deliberately dependency free: the log file is the
 * first thing we ask users for, so it must work even before the UI exists.
 * Listeners let the in-app log viewer follow new lines live.
 */
public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final List<Consumer<String>> LISTENERS = new ArrayList<>();
    private static final List<String> RECENT = new ArrayList<>();
    private static final int RECENT_LIMIT = 2000;
    private static Path file;

    private Log() {
    }

    public static synchronized void init() {
        try {
            Path logs = AppDirs.logs();
            Files.createDirectories(logs);
            file = logs.resolve("launcher.log");
            if (Files.exists(file) && Files.size(file) > 4L * 1024 * 1024) {
                Files.move(file, logs.resolve("launcher.old.log"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            file = null;
            System.err.println("Cannot open log file: " + error);
        }
    }

    public static void info(String message) {
        write("INFO ", message);
    }

    public static void warn(String message) {
        write("WARN ", message);
    }

    public static void error(String message) {
        write("ERROR", message);
    }

    public static void error(String message, Throwable error) {
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        write("ERROR", message + System.lineSeparator() + stack);
    }

    public static void debug(String message) {
        if (Boolean.getBoolean("petus.debug")) {
            write("DEBUG", message);
        }
    }

    private static synchronized void write(String level, String message) {
        String line = LocalDateTime.now().format(TIME) + " " + level + " " + message;
        System.out.println(line);
        RECENT.add(line);
        if (RECENT.size() > RECENT_LIMIT) {
            RECENT.remove(0);
        }
        for (Consumer<String> listener : List.copyOf(LISTENERS)) {
            try {
                listener.accept(line);
            } catch (RuntimeException ignored) {
                // A broken listener must never break logging.
            }
        }
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Losing a log line is never worth crashing over.
        }
    }

    public static synchronized List<String> recent() {
        return List.copyOf(RECENT);
    }

    public static synchronized void addListener(Consumer<String> listener) {
        LISTENERS.add(listener);
    }

    public static synchronized void removeListener(Consumer<String> listener) {
        LISTENERS.remove(listener);
    }

    public static Path fileLocation() {
        return file;
    }
}
