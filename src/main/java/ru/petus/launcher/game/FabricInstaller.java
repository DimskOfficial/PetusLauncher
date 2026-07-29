package ru.petus.launcher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ru.petus.launcher.core.Json;
import ru.petus.launcher.core.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fabric loader installer built on meta.fabricmc.net. We deliberately do not
 * run the official installer jar: fetching the launcher profile json ourselves
 * keeps everything in our shared version tree and lets MinecraftInstaller
 * download the loader libraries exactly like vanilla ones.
 */
public final class FabricInstaller {
    private static final String META = "https://meta.fabricmc.net/v2";

    private final MinecraftInstaller minecraft;
    private final Downloader downloader;

    public FabricInstaller(MinecraftInstaller minecraft) {
        this.minecraft = minecraft;
        this.downloader = minecraft.downloader();
    }

    public record LoaderVersion(String version, boolean stable) {
    }

    /** Loader versions compatible with a given Minecraft version. */
    public List<LoaderVersion> loaders(String gameVersion) throws IOException {
        String raw = downloader.getString(META + "/versions/loader/" + gameVersion);
        JsonArray array = com.google.gson.JsonParser.parseString(raw).getAsJsonArray();
        List<LoaderVersion> loaders = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject loader = element.getAsJsonObject().getAsJsonObject("loader");
            loaders.add(new LoaderVersion(Json.string(loader, "version", ""),
                    Json.bool(loader, "stable", false)));
        }
        return loaders;
    }

    public String resolveLoaderVersion(String gameVersion, String requested) throws IOException {
        List<LoaderVersion> loaders = loaders(gameVersion);
        if (loaders.isEmpty()) {
            throw new IOException("Fabric пока не поддерживает Minecraft " + gameVersion);
        }
        if (requested != null && !requested.isBlank() && !"latest".equals(requested)) {
            return loaders.stream()
                    .map(LoaderVersion::version)
                    .filter(requested::equals)
                    .findFirst()
                    .orElseThrow(() -> new IOException("Fabric loader " + requested + " недоступен для "
                            + gameVersion));
        }
        return loaders.stream()
                .filter(LoaderVersion::stable)
                .map(LoaderVersion::version)
                .findFirst()
                .orElse(loaders.get(0).version());
    }

    public static String profileId(String gameVersion, String loaderVersion) {
        return "fabric-loader-" + loaderVersion + "-" + gameVersion;
    }

    /**
     * Makes sure the Fabric profile for {@code gameVersion} exists locally and
     * installs it (plus vanilla). Returns the resolved version json.
     */
    public JsonObject install(String gameVersion, String requestedLoader, Progress progress) throws IOException {
        progress.stage("Готовим Fabric для " + gameVersion);
        String loaderVersion = resolveLoaderVersion(gameVersion, requestedLoader);
        String profileId = profileId(gameVersion, loaderVersion);
        Path profileJson = minecraft.versionJson(profileId);
        if (!Files.exists(profileJson)) {
            progress.detail("Fabric loader " + loaderVersion);
            String raw = downloader.getString(META + "/versions/loader/" + gameVersion + "/" + loaderVersion
                    + "/profile/json");
            JsonObject profile = Json.parseObject(raw);
            profile.addProperty("id", profileId);
            profile.addProperty("inheritsFrom", gameVersion);
            Files.createDirectories(profileJson.getParent());
            Files.writeString(profileJson, profile.toString(), StandardCharsets.UTF_8);
            Log.info("Installed Fabric profile " + profileId);
        }
        return minecraft.install(profileId, progress);
    }

    /** Version id to launch for a server that asks for a loader. */
    public String versionIdFor(String loader, String gameVersion, String requestedLoader) throws IOException {
        if (loader == null || "vanilla".equals(loader)) {
            return gameVersion;
        }
        if (!"fabric".equals(loader)) {
            throw new IOException("Пока поддерживается только Fabric, а сервер требует " + loader);
        }
        return profileId(gameVersion, resolveLoaderVersion(gameVersion, requestedLoader));
    }
}
