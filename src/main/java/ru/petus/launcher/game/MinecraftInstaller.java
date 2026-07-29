package ru.petus.launcher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ru.petus.launcher.core.AppDirs;
import ru.petus.launcher.core.Json;
import ru.petus.launcher.core.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vanilla Minecraft installer: version manifest, client jar, libraries,
 * natives and assets. Everything lands in one shared directory tree so several
 * instances (PetusCreate / PetusMC / a custom version) reuse the same files.
 *
 * Layout (under %APPDATA%/PetusLauncher/shared):
 *   versions/&lt;id&gt;/&lt;id&gt;.json     version metadata
 *   versions/&lt;id&gt;/&lt;id&gt;.jar      client jar
 *   versions/&lt;id&gt;/natives/       extracted platform natives
 *   libraries/...                 maven-style library tree
 *   assets/indexes, assets/objects
 */
public final class MinecraftInstaller {
    public static final String VERSION_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public record VersionSummary(String id, String type, String url, String releaseTime, String sha1) {
        public boolean release() {
            return "release".equals(type);
        }
    }

    private final Downloader downloader = new Downloader();

    public Downloader downloader() {
        return downloader;
    }

    // --- manifest --------------------------------------------------------
    /** Mojang's version list, cached for a day so the UI opens instantly. */
    public List<VersionSummary> availableVersions() throws IOException {
        Path cache = AppDirs.cache().resolve("version_manifest_v2.json");
        String raw = null;
        boolean fresh = Files.exists(cache)
                && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 24 * 3600_000L;
        if (fresh) {
            raw = Files.readString(cache, StandardCharsets.UTF_8);
        }
        if (raw == null) {
            try {
                raw = downloader.getString(VERSION_MANIFEST);
                Files.createDirectories(cache.getParent());
                Files.writeString(cache, raw, StandardCharsets.UTF_8);
            } catch (IOException offline) {
                if (!Files.exists(cache)) {
                    throw offline;
                }
                Log.warn("Using the cached version manifest: " + offline.getMessage());
                raw = Files.readString(cache, StandardCharsets.UTF_8);
            }
        }

        List<VersionSummary> versions = new ArrayList<>();
        JsonArray array = Json.parseObject(raw).getAsJsonArray("versions");
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            versions.add(new VersionSummary(
                    Json.string(object, "id", ""),
                    Json.string(object, "type", "release"),
                    Json.string(object, "url", ""),
                    Json.string(object, "releaseTime", ""),
                    Json.string(object, "sha1", null)));
        }
        return versions;
    }

    public VersionSummary findVersion(String id) throws IOException {
        return availableVersions().stream()
                .filter(version -> version.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IOException("Версия " + id + " не найдена в манифесте Mojang"));
    }

    // --- version json ----------------------------------------------------
    public Path versionDir(String id) {
        return AppDirs.shared().resolve("versions").resolve(id);
    }

    public Path versionJson(String id) {
        return versionDir(id).resolve(id + ".json");
    }

    public Path clientJar(String id) {
        return versionDir(id).resolve(id + ".jar");
    }

    public Path nativesDir(String id) {
        return versionDir(id).resolve("natives");
    }

    public Path librariesDir() {
        return AppDirs.shared().resolve("libraries");
    }

    public Path assetsDir() {
        return AppDirs.shared().resolve("assets");
    }

    /** Loads a local version json, downloading it from Mojang when missing. */
    public JsonObject loadVersionJson(String id, Progress progress) throws IOException {
        Path target = versionJson(id);
        if (!Files.exists(target)) {
            progress.detail("Метаданные версии " + id);
            VersionSummary summary = findVersion(id);
            Files.createDirectories(target.getParent());
            Files.writeString(target, downloader.getString(summary.url()), StandardCharsets.UTF_8);
        }
        return Json.parseObject(Files.readString(target, StandardCharsets.UTF_8));
    }

    /**
     * Resolves inheritance (Fabric profiles set inheritsFrom) into one json:
     * child libraries first, child arguments appended to the parent's.
     */
    public JsonObject resolve(String id, Progress progress) throws IOException {
        JsonObject version = loadVersionJson(id, progress);
        String parentId = Json.string(version, "inheritsFrom", null);
        if (parentId == null) {
            return version;
        }
        JsonObject parent = resolve(parentId, progress);
        return merge(parent, version);
    }

    private JsonObject merge(JsonObject parent, JsonObject child) {
        JsonObject merged = parent.deepCopy();
        for (String key : child.keySet()) {
            switch (key) {
                case "libraries" -> {
                    JsonArray libraries = new JsonArray();
                    child.getAsJsonArray("libraries").forEach(libraries::add);
                    if (parent.has("libraries")) {
                        parent.getAsJsonArray("libraries").forEach(libraries::add);
                    }
                    merged.add("libraries", libraries);
                }
                case "arguments" -> {
                    JsonObject arguments = new JsonObject();
                    JsonObject parentArguments = parent.has("arguments")
                            ? parent.getAsJsonObject("arguments") : new JsonObject();
                    JsonObject childArguments = child.getAsJsonObject("arguments");
                    for (String side : new String[] { "game", "jvm" }) {
                        JsonArray combined = new JsonArray();
                        if (parentArguments.has(side)) {
                            parentArguments.getAsJsonArray(side).forEach(combined::add);
                        }
                        if (childArguments.has(side)) {
                            childArguments.getAsJsonArray(side).forEach(combined::add);
                        }
                        arguments.add(side, combined);
                    }
                    merged.add("arguments", arguments);
                }
                case "inheritsFrom", "id" -> merged.add(key, child.get(key));
                default -> merged.add(key, child.get(key));
            }
        }
        merged.addProperty("id", Json.string(child, "id", Json.string(parent, "id", "unknown")));
        return merged;
    }

    // --- install ---------------------------------------------------------
    /**
     * Downloads everything needed to launch {@code id} and returns the resolved
     * version json. Safe to call repeatedly: existing valid files are skipped.
     */
    public JsonObject install(String id, Progress progress) throws IOException {
        progress.stage("Проверяем версию " + id);
        JsonObject version = resolve(id, progress);
        String clientVersionId = Json.string(version, "inheritsFrom", id);

        List<Downloader.Task> tasks = new ArrayList<>();

        // client jar (of the base version, so Fabric reuses the vanilla jar)
        JsonObject downloads = version.has("downloads") ? version.getAsJsonObject("downloads") : null;
        if (downloads != null && downloads.has("client")) {
            JsonObject client = downloads.getAsJsonObject("client");
            tasks.add(new Downloader.Task(Json.string(client, "url", ""), clientJar(clientVersionId),
                    Json.string(client, "sha1", null), client.get("size").getAsLong(),
                    "minecraft-" + clientVersionId + ".jar"));
        }

        // libraries + natives
        List<Path> nativeArchives = new ArrayList<>();
        for (JsonElement element : version.getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            if (!rulesAllow(library)) {
                continue;
            }
            JsonObject libraryDownloads = library.has("downloads")
                    ? library.getAsJsonObject("downloads") : null;
            if (libraryDownloads != null && libraryDownloads.has("artifact")) {
                JsonObject artifact = libraryDownloads.getAsJsonObject("artifact");
                Path target = librariesDir().resolve(Json.string(artifact, "path",
                        mavenPath(Json.string(library, "name", ""))));
                tasks.add(new Downloader.Task(Json.string(artifact, "url", ""), target,
                        Json.string(artifact, "sha1", null), artifact.has("size")
                                ? artifact.get("size").getAsLong() : 0,
                        target.getFileName().toString()));
            } else if (library.has("name") && library.has("url")) {
                // Fabric style library: maven root + coordinates only.
                String path = mavenPath(Json.string(library, "name", ""));
                tasks.add(new Downloader.Task(Json.string(library, "url", "").replaceAll("/+$", "") + "/" + path,
                        librariesDir().resolve(path), null, 0, path));
            }

            String classifier = nativeClassifier(library);
            if (classifier != null && libraryDownloads != null && libraryDownloads.has("classifiers")) {
                JsonObject classifiers = libraryDownloads.getAsJsonObject("classifiers");
                if (classifiers.has(classifier)) {
                    JsonObject artifact = classifiers.getAsJsonObject(classifier);
                    Path target = librariesDir().resolve(Json.string(artifact, "path", ""));
                    tasks.add(new Downloader.Task(Json.string(artifact, "url", ""), target,
                            Json.string(artifact, "sha1", null),
                            artifact.has("size") ? artifact.get("size").getAsLong() : 0,
                            target.getFileName().toString()));
                    nativeArchives.add(target);
                }
            }
        }

        // asset index
        JsonObject assetIndex = version.has("assetIndex") ? version.getAsJsonObject("assetIndex") : null;
        Path assetIndexFile = null;
        if (assetIndex != null) {
            assetIndexFile = assetsDir().resolve("indexes").resolve(Json.string(assetIndex, "id", "legacy") + ".json");
            tasks.add(new Downloader.Task(Json.string(assetIndex, "url", ""), assetIndexFile,
                    Json.string(assetIndex, "sha1", null),
                    assetIndex.has("size") ? assetIndex.get("size").getAsLong() : 0, "asset index"));
        }

        // logging config (keeps the game's log4j output readable)
        if (version.has("logging") && version.getAsJsonObject("logging").has("client")) {
            JsonObject file = version.getAsJsonObject("logging").getAsJsonObject("client")
                    .getAsJsonObject("file");
            tasks.add(new Downloader.Task(Json.string(file, "url", ""),
                    assetsDir().resolve("log_configs").resolve(Json.string(file, "id", "client.xml")),
                    Json.string(file, "sha1", null), file.has("size") ? file.get("size").getAsLong() : 0,
                    "logging config"));
        }

        List<String> failures = new ArrayList<>(
                downloader.downloadAll(tasks, progress, "Скачиваем клиент и библиотеки"));

        // assets (thousands of small files — always the longest step)
        if (assetIndexFile != null && Files.exists(assetIndexFile)) {
            failures.addAll(downloadAssets(assetIndexFile, progress));
        }

        // natives
        if (!nativeArchives.isEmpty()) {
            progress.stage("Распаковываем нативные библиотеки");
            Path natives = nativesDir(id);
            Files.createDirectories(natives);
            for (Path archive : nativeArchives) {
                extractNatives(archive, natives);
            }
        }

        if (!failures.isEmpty()) {
            throw new IOException("Не удалось скачать " + failures.size() + " файл(ов): "
                    + String.join("; ", failures.subList(0, Math.min(3, failures.size()))));
        }
        progress.stage("Версия " + id + " готова");
        progress.fraction(1);
        return version;
    }

    private List<String> downloadAssets(Path assetIndexFile, Progress progress) throws IOException {
        JsonObject index = Json.parseObject(Files.readString(assetIndexFile, StandardCharsets.UTF_8));
        if (!index.has("objects")) {
            return List.of();
        }
        JsonObject objects = index.getAsJsonObject("objects");
        List<Downloader.Task> tasks = new ArrayList<>(objects.size());
        for (String name : objects.keySet()) {
            JsonObject object = objects.getAsJsonObject(name);
            String hash = Json.string(object, "hash", "");
            String prefix = hash.substring(0, 2);
            tasks.add(new Downloader.Task("https://resources.download.minecraft.net/" + prefix + "/" + hash,
                    assetsDir().resolve("objects").resolve(prefix).resolve(hash), hash,
                    object.has("size") ? object.get("size").getAsLong() : 0, name));
        }
        return downloader.downloadAll(tasks, progress, "Скачиваем ресурсы игры");
    }

    private void extractNatives(Path archive, Path target) throws IOException {
        try (InputStream input = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                Path destination = target.resolve(Path.of(entry.getName()).getFileName().toString())
                        .normalize();
                if (!destination.startsWith(target)) {
                    continue; // zip slip guard
                }
                Files.copy(zip, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // --- classpath / rules ----------------------------------------------
    /** Full classpath for a resolved version json, client jar included. */
    public List<Path> classpath(JsonObject version) {
        Set<Path> entries = new LinkedHashSet<>();
        Map<String, Path> byArtifact = new LinkedHashMap<>();
        for (JsonElement element : version.getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            if (!rulesAllow(library)) {
                continue;
            }
            String name = Json.string(library, "name", "");
            Path path;
            if (library.has("downloads") && library.getAsJsonObject("downloads").has("artifact")) {
                path = librariesDir().resolve(Json.string(
                        library.getAsJsonObject("downloads").getAsJsonObject("artifact"), "path",
                        mavenPath(name)));
            } else {
                path = librariesDir().resolve(mavenPath(name));
            }
            // Keep the first occurrence of group:artifact — child versions win.
            String key = artifactKey(name);
            if (!byArtifact.containsKey(key)) {
                byArtifact.put(key, path);
            }
        }
        entries.addAll(byArtifact.values());
        entries.add(clientJar(Json.string(version, "inheritsFrom", Json.string(version, "id", ""))));
        return List.copyOf(entries);
    }

    private static String artifactKey(String name) {
        String[] parts = name.split(":");
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : name;
    }

    /** "net.fabricmc:fabric-loader:0.16.10" -&gt; net/fabricmc/fabric-loader/... */
    public static String mavenPath(String coordinates) {
        String[] parts = coordinates.split(":");
        if (parts.length < 3) {
            return coordinates.replace(':', '/');
        }
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
                + parts[1] + "-" + parts[2] + classifier + ".jar";
    }

    private String nativeClassifier(JsonObject library) {
        if (!library.has("natives")) {
            return null;
        }
        JsonObject natives = library.getAsJsonObject("natives");
        String key = AppDirs.osName();
        if (!natives.has(key)) {
            return null;
        }
        return Json.string(natives, key, "").replace("${arch}", AppDirs.arch().contains("64") ? "64" : "32");
    }

    /** Evaluates the standard allow/disallow rule blocks against this host. */
    public static boolean rulesAllow(JsonObject owner) {
        if (!owner.has("rules")) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : owner.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !Json.string(os, "name", "").equals(AppDirs.osName())) {
                    matches = false;
                }
                if (os.has("arch")) {
                    String arch = Json.string(os, "arch", "");
                    String hostArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
                    if (!hostArch.contains(arch.replace("x86", "86"))) {
                        matches = false;
                    }
                }
                if (os.has("version")) {
                    String pattern = Json.string(os, "version", ".*");
                    if (!System.getProperty("os.version", "").matches(pattern)) {
                        matches = false;
                    }
                }
            }
            if (rule.has("features")) {
                // Demo mode / custom resolution features are never requested.
                matches = false;
            }
            if (matches) {
                allowed = "allow".equals(Json.string(rule, "action", "allow"));
            }
        }
        return allowed;
    }
}
