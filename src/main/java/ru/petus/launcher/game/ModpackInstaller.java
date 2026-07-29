package ru.petus.launcher.game;

import ru.petus.launcher.api.ApiClient;
import ru.petus.launcher.api.Models;
import ru.petus.launcher.core.AppDirs;
import ru.petus.launcher.core.Json;
import ru.petus.launcher.core.Log;
import ru.petus.launcher.core.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Installs a server's modpack into its instance folder.
 *
 * Rules that come from how our servers actually work:
 *   - mods marked required are always installed (petus-connect, Fabric API,
 *     Create on PetusCreate, Meteor on the anarchy server …)
 *   - optional mods follow the user's per-server toggles
 *   - server side ("both") and client-only mods are both installed on the client
 *   - files we installed earlier but that are no longer wanted are removed, so
 *     switching a toggle off actually disables the cheat/mod
 *   - manually added jars (not in our manifest) are never touched
 */
public final class ModpackInstaller {
    /** State file describing what we put into the mods folder. */
    public static final class InstalledPack {
        public String gameVersion = "";
        public String loader = "";
        /** modId -&gt; file name */
        public Map<String, String> files = new LinkedHashMap<>();
    }

    private final Downloader downloader;

    public ModpackInstaller(Downloader downloader) {
        this.downloader = downloader;
    }

    public Path modsDir(String serverId) {
        return AppDirs.instance(serverId).resolve("mods");
    }

    private Path stateFile(String serverId) {
        return AppDirs.instance(serverId).resolve("petus-modpack.json");
    }

    private InstalledPack readState(String serverId) {
        try {
            Path file = stateFile(serverId);
            if (Files.exists(file)) {
                InstalledPack state = Json.read(file, InstalledPack.class);
                if (state != null) {
                    if (state.files == null) {
                        state.files = new LinkedHashMap<>();
                    }
                    return state;
                }
            }
        } catch (Exception error) {
            Log.warn("Cannot read the modpack state of " + serverId + ": " + error);
        }
        return new InstalledPack();
    }

    /** Mods that should end up in the instance for the current settings. */
    public List<Models.ModEntry> wanted(Models.ServerEntry server) {
        List<Models.ModEntry> wanted = new ArrayList<>();
        for (Models.ModEntry mod : server.mods) {
            boolean enabled = mod.required
                    || Settings.get().modEnabled(server.id, mod.id, mod.enabledByDefault);
            if (enabled) {
                wanted.add(mod);
            }
        }
        return wanted;
    }

    /**
     * Downloads/updates the modpack. Returns the list of non fatal problems
     * (e.g. a mod that has no build for the chosen Minecraft version yet).
     */
    public List<String> install(Models.ServerEntry server, String gameVersion, Progress progress)
            throws IOException {
        String loader = server.loader == null ? "fabric" : server.loader;
        Path mods = modsDir(server.id);
        Files.createDirectories(mods);

        InstalledPack previous = readState(server.id);
        InstalledPack current = new InstalledPack();
        current.gameVersion = gameVersion;
        current.loader = loader;

        List<Models.ModEntry> wanted = wanted(server);
        List<String> warnings = new ArrayList<>();
        List<Downloader.Task> tasks = new ArrayList<>();

        progress.stage("Подбираем моды для " + gameVersion);
        int index = 0;
        for (Models.ModEntry mod : wanted) {
            progress.checkCancelled();
            progress.detail(mod.name);
            progress.fraction(++index / (double) Math.max(1, wanted.size()));
            try {
                if ("modrinth".equals(mod.source)) {
                    Models.ModResolve resolved = ApiClient.get()
                            .resolveMod(mod.slug == null ? mod.id : mod.slug, gameVersion, loader);
                    tasks.add(new Downloader.Task(resolved.url, mods.resolve(resolved.fileName), resolved.sha1,
                            resolved.size, mod.name));
                    current.files.put(mod.id, resolved.fileName);
                } else if (mod.url != null && !mod.url.isBlank()) {
                    String fileName = mod.id + ".jar";
                    tasks.add(new Downloader.Task(mod.url, mods.resolve(fileName), mod.sha1, 0, mod.name));
                    current.files.put(mod.id, fileName);
                } else {
                    warnings.add(mod.name + ": нет ссылки на файл");
                }
            } catch (RuntimeException error) {
                String message = mod.name + ": " + error.getMessage();
                if (mod.required) {
                    throw new IOException("Обязательный мод недоступен — " + message);
                }
                Log.warn("Skipping optional mod " + mod.id + ": " + error.getMessage());
                warnings.add(message);
            }
        }

        warnings.addAll(downloader.downloadAll(tasks, progress, "Скачиваем моды"));

        // Drop files we installed before and no longer want.
        for (Map.Entry<String, String> entry : previous.files.entrySet()) {
            String fileName = entry.getValue();
            boolean stillWanted = current.files.containsValue(fileName);
            if (!stillWanted) {
                try {
                    if (Files.deleteIfExists(mods.resolve(fileName))) {
                        Log.info("Removed " + fileName + " from " + server.id);
                    }
                } catch (IOException error) {
                    warnings.add("Не удалось удалить " + fileName + ": " + error.getMessage());
                }
            }
        }

        Json.write(stateFile(server.id), current);
        progress.fraction(1);
        progress.stage("Модпак готов");
        return warnings;
    }

    /** True when every required mod is present for this version. */
    public boolean installed(Models.ServerEntry server, String gameVersion) {
        InstalledPack state = readState(server.id);
        if (!gameVersion.equals(state.gameVersion)) {
            return false;
        }
        Path mods = modsDir(server.id);
        for (Models.ModEntry mod : server.mods) {
            if (!mod.required) {
                continue;
            }
            String fileName = state.files.get(mod.id);
            if (fileName == null || !Files.exists(mods.resolve(fileName))) {
                return false;
            }
        }
        return true;
    }

    public long installedSize(String serverId) {
        Path mods = modsDir(serverId);
        if (!Files.isDirectory(mods)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(mods)) {
            return files.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException error) {
                    return 0;
                }
            }).sum();
        } catch (IOException error) {
            return 0;
        }
    }
}
