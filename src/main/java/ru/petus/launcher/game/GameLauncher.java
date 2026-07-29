package ru.petus.launcher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ru.petus.launcher.Bootstrap;
import ru.petus.launcher.api.Models;
import ru.petus.launcher.auth.Account;
import ru.petus.launcher.core.AppDirs;
import ru.petus.launcher.core.Json;
import ru.petus.launcher.core.Log;
import ru.petus.launcher.core.Settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Builds the java command line for a resolved version json and starts the game.
 *
 * PetusID handoff: the join ticket is written to
 * &lt;instance&gt;/petus/ticket.json and also passed as -Dpetus.ticket / the
 * PETUS_TICKET environment variable. Our client mod (petus-connect) reads it
 * during login and sends it to the proxy, which is what lets players join our
 * servers without a password. The file is deleted as soon as the game reads it
 * (the mod truncates it) and always rewritten before a launch.
 */
public final class GameLauncher {
    public record LaunchRequest(
            Models.ServerEntry server,
            String versionId,
            JsonObject version,
            Account account,
            Models.JoinTicket ticket,
            boolean autoJoin) {
    }

    public Process launch(LaunchRequest request, Consumer<String> logConsumer) throws IOException {
        Models.ServerEntry server = request.server();
        Path instance = AppDirs.instance(server.id);
        Files.createDirectories(instance);

        writeTicket(instance, request);

        List<String> command = buildCommand(request, instance);
        Log.info("Launching " + server.id + " (" + request.versionId() + ")");
        Log.debug("Command: " + String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(instance.toFile())
                .redirectErrorStream(true);
        if (request.ticket() != null && request.ticket().ticket != null) {
            builder.environment().put("PETUS_TICKET", request.ticket().ticket);
            builder.environment().put("PETUS_SERVER_ID", server.id);
        }
        Process process = builder.start();
        pipeLogs(process, logConsumer);
        return process;
    }

    private void writeTicket(Path instance, LaunchRequest request) throws IOException {
        Path petusDir = instance.resolve("petus");
        Files.createDirectories(petusDir);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serverId", request.server().id);
        payload.put("address", request.server().address);
        payload.put("port", request.server().port);
        payload.put("username", request.account().name);
        payload.put("uuid", request.account().uuid);
        payload.put("premium", request.account().premium());
        payload.put("launcher", "PetusLauncher/" + Bootstrap.version());
        payload.put("issuedAt", System.currentTimeMillis());
        payload.put("ticket", request.ticket() == null ? null : request.ticket().ticket);
        payload.put("expiresIn", request.ticket() == null ? 0 : request.ticket().expiresIn);
        Json.write(petusDir.resolve("ticket.json"), payload);
    }

    // --- command line ----------------------------------------------------
    public List<String> buildCommand(LaunchRequest request, Path instance) throws IOException {
        Settings settings = Settings.get();
        JsonObject version = request.version();
        MinecraftInstaller minecraft = new MinecraftInstaller();
        String baseVersion = Json.string(version, "inheritsFrom", Json.string(version, "id", ""));

        List<Path> classpath = minecraft.classpath(version);
        String classpathString = classpath.stream()
                .map(Path::toString)
                .collect(Collectors.joining(File_pathSeparator()));

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("auth_player_name", request.account().name);
        variables.put("auth_uuid", OfflineUuid.compact(request.account().uuid));
        variables.put("auth_access_token", request.account().gameAccessToken());
        variables.put("auth_xuid", "");
        variables.put("user_type", request.account().userType());
        variables.put("clientid", "petus-launcher");
        variables.put("version_name", Json.string(version, "id", request.versionId()));
        variables.put("version_type", Json.string(version, "type", "release"));
        variables.put("game_directory", instance.toString());
        variables.put("assets_root", minecraft.assetsDir().toString());
        variables.put("game_assets", minecraft.assetsDir().resolve("virtual/legacy").toString());
        variables.put("assets_index_name", version.has("assetIndex")
                ? Json.string(version.getAsJsonObject("assetIndex"), "id", "legacy") : "legacy");
        variables.put("natives_directory", minecraft.nativesDir(Json.string(version, "id", baseVersion)).toString());
        variables.put("launcher_name", "PetusLauncher");
        variables.put("launcher_version", Bootstrap.version());
        variables.put("classpath", classpathString);
        variables.put("classpath_separator", File_pathSeparator());
        variables.put("library_directory", minecraft.librariesDir().toString());
        variables.put("resolution_width", String.valueOf(settings.windowWidth));
        variables.put("resolution_height", String.valueOf(settings.windowHeight));

        List<String> command = new ArrayList<>();
        command.add(JavaLocator.javaExecutable(request.server().javaMajor == null ? 21 : request.server().javaMajor));
        command.add("-Xms" + Math.min(1024, settings.memoryMb) + "M");
        command.add("-Xmx" + settings.memoryMb + "M");
        command.add("-Dpetus.launcher=" + Bootstrap.version());
        command.add("-Dpetus.serverId=" + request.server().id);
        if (request.ticket() != null && request.ticket().ticket != null) {
            command.add("-Dpetus.ticket=" + request.ticket().ticket);
        }
        if (AppDirs.os() == AppDirs.Os.MACOS) {
            command.add("-XstartOnFirstThread");
        }
        for (String argument : settings.jvmArgs.split("\\s+")) {
            if (!argument.isBlank()) {
                command.add(argument);
            }
        }
        if (version.has("logging") && version.getAsJsonObject("logging").has("client")) {
            JsonObject logging = version.getAsJsonObject("logging").getAsJsonObject("client");
            String id = Json.string(logging.getAsJsonObject("file"), "id", "");
            Path config = minecraft.assetsDir().resolve("log_configs").resolve(id);
            if (Files.exists(config)) {
                command.add(Json.string(logging, "argument", "-Dlog4j.configurationFile=${path}")
                        .replace("${path}", config.toString()));
            }
        }

        // JVM arguments from the version json (module path, natives, classpath).
        List<String> jvmArguments = arguments(version, "jvm");
        if (jvmArguments.isEmpty()) {
            command.add("-Djava.library.path=" + variables.get("natives_directory"));
            command.add("-cp");
            command.add(classpathString);
        } else {
            command.addAll(substitute(jvmArguments, variables));
        }

        command.add(Json.string(version, "mainClass", "net.minecraft.client.main.Main"));

        List<String> gameArguments = arguments(version, "game");
        if (gameArguments.isEmpty() && version.has("minecraftArguments")) {
            for (String argument : Json.string(version, "minecraftArguments", "").split(" ")) {
                if (!argument.isBlank()) {
                    gameArguments.add(argument);
                }
            }
        }
        command.addAll(substitute(gameArguments, variables));

        if (settings.fullscreen) {
            command.add("--fullscreen");
        } else {
            command.add("--width");
            command.add(String.valueOf(settings.windowWidth));
            command.add("--height");
            command.add(String.valueOf(settings.windowHeight));
        }

        if (request.autoJoin() && request.server().address != null) {
            // 1.20+ replaced --server/--port with quick play.
            String address = request.server().address
                    + (request.server().port == null ? "" : ":" + request.server().port);
            if (supportsQuickPlay(baseVersion)) {
                command.add("--quickPlayMultiplayer");
                command.add(address);
            } else {
                command.add("--server");
                command.add(request.server().address);
                command.add("--port");
                command.add(String.valueOf(request.server().port == null ? 25565 : request.server().port));
            }
        }
        return command;
    }

    private static boolean supportsQuickPlay(String version) {
        // Everything we support (1.21+ and the 26.x line) has quick play.
        return !version.startsWith("1.1") || version.startsWith("1.19") || version.startsWith("1.2");
    }

    private static String File_pathSeparator() {
        return java.io.File.pathSeparator;
    }

    /** Flattens an arguments array, honouring the rule blocks. */
    private List<String> arguments(JsonObject version, String side) {
        List<String> result = new ArrayList<>();
        if (!version.has("arguments")) {
            return result;
        }
        JsonObject arguments = version.getAsJsonObject("arguments");
        if (!arguments.has(side)) {
            return result;
        }
        for (JsonElement element : arguments.getAsJsonArray(side)) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
                continue;
            }
            JsonObject conditional = element.getAsJsonObject();
            if (!MinecraftInstaller.rulesAllow(conditional)) {
                continue;
            }
            JsonElement value = conditional.get("value");
            if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    result.add(item.getAsString());
                }
            } else {
                result.add(value.getAsString());
            }
        }
        return result;
    }

    private List<String> substitute(List<String> arguments, Map<String, String> variables) {
        List<String> result = new ArrayList<>(arguments.size());
        for (String argument : arguments) {
            String value = argument;
            for (Map.Entry<String, String> variable : variables.entrySet()) {
                value = value.replace("${" + variable.getKey() + "}", variable.getValue());
            }
            result.add(value);
        }
        return result;
    }

    private void pipeLogs(Process process, Consumer<String> consumer) {
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    consumer.accept(line);
                }
            } catch (IOException error) {
                Log.warn("Game log stream closed: " + error.getMessage());
            }
        }, "petus-game-log");
        reader.setDaemon(true);
        reader.start();
    }

    /** Unused arrays helper kept for clarity in tests. */
    static JsonArray emptyArray() {
        return new JsonArray();
    }
}
