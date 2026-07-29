package ru.petus.launcher.game;

import ru.petus.launcher.core.Log;
import ru.petus.launcher.core.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel downloader with resume-free but hash-verified semantics: a file is
 * only considered installed when its SHA-1 matches, otherwise it is fetched
 * again. Byte level progress is aggregated so the UI can show a real progress
 * bar and a live speed readout instead of a fake spinner.
 */
public final class Downloader {
    public record Task(String url, Path target, String sha1, long size, String label) {
        public Task(String url, Path target) {
            this(url, target, null, 0, target.getFileName().toString());
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpClient http() {
        return http;
    }

    // --- single files ----------------------------------------------------
    public String getString(String url) throws IOException {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(45))
                            .header("User-Agent", userAgent())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " для " + url);
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Загрузка прервана");
        }
    }

    /** Downloads one file, verifying SHA-1 when it is known. */
    public void download(Task task) throws IOException {
        if (isValid(task)) {
            return;
        }
        Files.createDirectories(task.target().getParent());
        Path temporary = task.target().resolveSibling(task.target().getFileName() + ".part");
        try {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(task.url()))
                            .timeout(Duration.ofMinutes(10))
                            .header("User-Agent", userAgent())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " для " + task.url());
            }
            try (InputStream body = response.body()) {
                Files.copy(body, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (task.sha1() != null && Settings.get().verifyFileHashes) {
                String actual = sha1(temporary);
                if (!actual.equalsIgnoreCase(task.sha1())) {
                    Files.deleteIfExists(temporary);
                    throw new IOException("Не совпала контрольная сумма " + task.label());
                }
            }
            Files.move(temporary, task.target(), StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Загрузка прервана");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Downloads a batch in parallel and reports aggregated progress. Failures
     * are collected so one broken mirror does not abort the whole install.
     */
    public List<String> downloadAll(List<Task> tasks, Progress progress, String stage) {
        List<Task> pending = new ArrayList<>();
        for (Task task : tasks) {
            if (!isValid(task)) {
                pending.add(task);
            }
        }
        if (pending.isEmpty()) {
            progress.detail("Все файлы уже на месте");
            progress.fraction(1);
            return List.of();
        }

        progress.stage(stage);
        long totalBytes = pending.stream().mapToLong(Task::size).sum();
        AtomicLong doneBytes = new AtomicLong();
        AtomicLong doneFiles = new AtomicLong();
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        int threads = Math.max(1, Math.min(Settings.get().downloadThreads, pending.size()));

        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "petus-download");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Task task : pending) {
                futures.add(CompletableFuture.runAsync(() -> {
                    progress.checkCancelled();
                    try {
                        download(task);
                        doneBytes.addAndGet(task.size());
                    } catch (IOException error) {
                        Log.warn("Download failed: " + task.url() + " — " + error.getMessage());
                        failures.add(task.label() + ": " + error.getMessage());
                    }
                    long files = doneFiles.incrementAndGet();
                    progress.detail(task.label() + "  (" + files + "/" + pending.size() + ")");
                    progress.fraction(totalBytes > 0
                            ? Math.min(1, doneBytes.get() / (double) totalBytes)
                            : files / (double) pending.size());
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally {
            pool.shutdownNow();
        }
        progress.fraction(1);
        return List.copyOf(failures);
    }

    // --- helpers ---------------------------------------------------------
    private boolean isValid(Task task) {
        try {
            if (!Files.exists(task.target())) {
                return false;
            }
            if (task.sha1() != null && Settings.get().verifyFileHashes) {
                return sha1(task.target()).equalsIgnoreCase(task.sha1());
            }
            return task.size() <= 0 || Files.size(task.target()) == task.size();
        } catch (IOException error) {
            return false;
        }
    }

    public static String sha1(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
                DigestInputStream digest = new DigestInputStream(input, MessageDigest.getInstance("SHA-1"))) {
            byte[] buffer = new byte[1 << 16];
            while (digest.read(buffer) != -1) {
                // Reading is enough; the digest updates itself.
            }
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        String[] units = { "KB", "MB", "GB" };
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String userAgent() {
        return "PetusLauncher/" + ru.petus.launcher.Bootstrap.version();
    }
}
