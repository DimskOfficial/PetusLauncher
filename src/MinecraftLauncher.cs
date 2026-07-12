using System.Diagnostics;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace PetusLauncher;

// Full vanilla-Minecraft install + launch backend. Talks to Mojang's piston
// endpoints, mirrors the standard .minecraft layout under Config.McDir, pulls a
// matching Mojang Java runtime, and starts the game with a real launch command.
// No integrity/hash gating on launch (PetusMC intentionally has none) — we only
// use sizes to skip already-downloaded files.
static class MinecraftLauncher
{
    const string ManifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    const string JavaRuntimesUrl = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    const string AssetsCdn = "https://resources.download.minecraft.net";
    const string LibrariesCdn = "https://libraries.minecraft.net";

    // Forge + Meteor (PetusMC always launches Forge with the Meteor mod).
    const string ForgePromotions = "https://files.minecraftforge.net/net/maven/net/minecraftforge/forge/promotions_slim.json";
    const string ForgeMaven = "https://maven.minecraftforge.net/net/minecraftforge/forge";
    const string MeteorDownload = "https://meteorclient.com/api/download";

    static readonly HttpClient Http = MakeClient();

    static HttpClient MakeClient()
    {
        // The Mojang CDN rejects empty User-Agents — always send one.
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("PetusLauncher/2.1");
        return c;
    }

    // --- standard .minecraft-style layout under McDir ---
    static string VersionsDir => Path.Combine(Config.McDir, "versions");
    static string LibrariesDir => Path.Combine(Config.McDir, "libraries");
    static string AssetsDir => Path.Combine(Config.McDir, "assets");
    static string RuntimeDir => Path.Combine(Config.McDir, "runtime");
    static string NativesRoot => Path.Combine(Config.McDir, "natives");

    static string VersionJsonPath(string id) => Path.Combine(VersionsDir, id, id + ".json");
    static string VersionJarPath(string id) => Path.Combine(VersionsDir, id, id + ".jar");
    static string NativesDir(string id) => Path.Combine(NativesRoot, id);

    // Release version ids from Mojang, newest first (manifest is already ordered
    // newest→oldest). Snapshots dropped. Config.McServerVersion is guaranteed to
    // appear even if the manifest lists it differently or not at all.
    public static async Task<List<string>> GetVersionsAsync()
    {
        using var doc = await GetJsonAsync(ManifestUrl);
        var list = new List<string>();
        foreach (var v in doc.RootElement.GetProperty("versions").EnumerateArray())
        {
            if (v.GetProperty("type").GetString() == "release")
            {
                var id = v.GetProperty("id").GetString();
                if (!string.IsNullOrEmpty(id)) list.Add(id);
            }
        }
        if (!list.Contains(Config.McServerVersion))
            list.Insert(0, Config.McServerVersion);
        return list;
    }

    // True when both the client jar and the version json are on disk.
    public static bool IsInstalled(string versionId)
        => File.Exists(VersionJarPath(versionId)) && File.Exists(VersionJsonPath(versionId));

    // Install everything for a version, then launch the game. See interface docs.
    // When forgeMeteor is true (PetusMC), Forge is installed for the version and
    // the Meteor client mod is placed in mods/ before launching the Forge profile.
    public static async Task<Process> InstallAndLaunchAsync(
        string versionId, McAccount account, int ramMb,
        Action<string, double> progress, string? serverIp, bool forgeMeteor = false)
    {
        Directory.CreateDirectory(Config.McDir);

        // 1) Vanilla version json + client jar. -----------------------------
        progress("manifest", 0);
        var vanillaDoc = await EnsureVersionJsonAsync(versionId);
        var vanillaRoot = vanillaDoc.RootElement;
        progress("manifest", 0.4);

        var jarPath = VersionJarPath(versionId);
        if (vanillaRoot.TryGetProperty("downloads", out var dl0) && dl0.TryGetProperty("client", out var client0))
            await DownloadFileAsync(client0.GetProperty("url").GetString()!, jarPath, SizeOf(client0));
        progress("manifest", 1);

        // Effective launch root + version id. For Forge this is the merged
        // (vanilla ⊕ forge) json; for vanilla it's just the vanilla root.
        JsonDocument? mergedDoc = null;
        JsonElement root = vanillaRoot;
        string launchId = versionId;

        if (forgeMeteor)
        {
            // Java is needed to run the Forge installer headlessly.
            var javaForInstaller = await EnsureJavaAsync(vanillaRoot, progress);
            var forgeId = await EnsureForgeAsync(versionId, javaForInstaller, progress);

            using var forgeDoc = JsonDocument.Parse(await File.ReadAllTextAsync(VersionJsonPath(forgeId)));
            mergedDoc = MergeInherited(vanillaRoot, forgeDoc.RootElement);
            root = mergedDoc.RootElement;
            launchId = forgeId;

            // Meteor client mod → mods/.
            await EnsureMeteorAsync(progress);
        }

        try
        {
            // 2) Libraries + native extraction. ------------------------------
            var (classpath, nativeJars) = await DownloadLibrariesAsync(root, progress);
            ExtractNatives(nativeJars, NativesDir(versionId));

            // 3) Assets (from the vanilla/inherited json). -------------------
            var assetIndexId = await DownloadAssetsAsync(root, progress);

            // 4) Java runtime. -----------------------------------------------
            var javaw = await EnsureJavaAsync(root, progress);

            // 5) Build the launch command + start. ---------------------------
            progress("launch", 0);
            classpath.Add(jarPath); // client jar goes on the classpath last
            var psi = BuildStartInfo(root, launchId, account, ramMb, serverIp,
                javaw, classpath, assetIndexId, versionId);

            var proc = new Process { StartInfo = psi, EnableRaisingEvents = true };
            proc.Start();
            progress("launch", 1);
            return proc;
        }
        finally
        {
            mergedDoc?.Dispose();
        }
    }

    // ------------------------------------------------------------------ forge

    // Resolve, download and headlessly install Forge for a Minecraft version.
    // Returns the installed Forge version-profile id (folder under versions/).
    // Skips work if the profile already exists.
    static async Task<string> EnsureForgeAsync(string mcVersion, string javaw, Action<string, double> progress)
    {
        progress("forge", 0);
        var forgeVer = await ResolveForgeVersionAsync(mcVersion);
        var forgeId = $"{mcVersion}-forge-{forgeVer}";
        if (File.Exists(VersionJsonPath(forgeId))) { progress("forge", 1); return forgeId; }

        // Download the Forge installer jar.
        var installerUrl = $"{ForgeMaven}/{mcVersion}-{forgeVer}/forge-{mcVersion}-{forgeVer}-installer.jar";
        var installer = Path.Combine(Config.McDir, $"forge-{mcVersion}-{forgeVer}-installer.jar");
        await DownloadFileAsync(installerUrl, installer, 0);
        progress("forge", 0.4);

        // Modern installers require a launcher_profiles.json in the target dir.
        var profiles = Path.Combine(Config.McDir, "launcher_profiles.json");
        if (!File.Exists(profiles))
            await File.WriteAllTextAsync(profiles, "{\"profiles\":{},\"selectedProfile\":\"\",\"clientToken\":\"petus\"}");

        // Run: java -jar forge-installer.jar --installClient <McDir>
        var java = javaw.Replace("javaw.exe", "java.exe");
        if (!File.Exists(java)) java = javaw;
        var psi = new ProcessStartInfo
        {
            FileName = java,
            WorkingDirectory = Config.McDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.ArgumentList.Add("-jar");
        psi.ArgumentList.Add(installer);
        psi.ArgumentList.Add("--installClient");
        psi.ArgumentList.Add(Config.McDir);

        using (var proc = new Process { StartInfo = psi })
        {
            proc.Start();
            // Drain both pipes concurrently so a chatty installer can't deadlock.
            var stdout = proc.StandardOutput.ReadToEndAsync();
            var stderr = proc.StandardError.ReadToEndAsync();
            await proc.WaitForExitAsync();
            await Task.WhenAll(stdout, stderr);
            if (!File.Exists(VersionJsonPath(forgeId)))
                throw new Exception($"Установщик Forge завершился, но профиль {forgeId} не создан.\n{await stderr}");
        }
        try { File.Delete(installer); } catch { }
        try { File.Delete(installer + ".log"); } catch { }
        progress("forge", 1);
        return forgeId;
    }

    // Pick the recommended (else latest) Forge build for a Minecraft version.
    static async Task<string> ResolveForgeVersionAsync(string mcVersion)
    {
        using var doc = await GetJsonAsync(ForgePromotions);
        if (!doc.RootElement.TryGetProperty("promos", out var promos))
            throw new Exception("Не удалось получить список версий Forge.");
        string? Get(string key) => promos.TryGetProperty(key, out var v) ? v.GetString() : null;
        var forge = Get($"{mcVersion}-recommended") ?? Get($"{mcVersion}-latest");
        if (string.IsNullOrEmpty(forge))
            throw new Exception($"Для Minecraft {mcVersion} нет доступной версии Forge.");
        return forge;
    }

    // ----------------------------------------------------------------- meteor

    // Always (re)ensure the Meteor client mod is in mods/. Downloads from the
    // official endpoint (302 → latest jar), following redirects with a UA.
    static async Task EnsureMeteorAsync(Action<string, double> progress)
    {
        progress("meteor", 0);
        var mods = Path.Combine(Config.McDir, "mods");
        Directory.CreateDirectory(mods);
        var jar = Path.Combine(mods, "meteor-client.jar");
        if (File.Exists(jar) && new FileInfo(jar).Length > 0) { progress("meteor", 1); return; }

        await WithRetry(async () =>
        {
            using var resp = await Http.GetAsync(MeteorDownload, HttpCompletionOption.ResponseHeadersRead);
            resp.EnsureSuccessStatusCode();
            var tmp = jar + ".part";
            await using (var src = await resp.Content.ReadAsStreamAsync())
            await using (var dst = File.Create(tmp))
                await src.CopyToAsync(dst);
            if (File.Exists(jar)) File.Delete(jar);
            File.Move(tmp, jar);
            return true;
        });
        progress("meteor", 1);
    }

    // Merge a child version json (inheritsFrom) with its parent into one root.
    // Child wins for mainClass; libraries are child-first then parent; argument
    // arrays are parent-then-child; assets/downloads/javaVersion come from parent.
    static JsonDocument MergeInherited(JsonElement parent, JsonElement child)
    {
        var o = new JsonObject();

        string mainClass = child.TryGetProperty("mainClass", out var mc) ? mc.GetString() ?? ""
            : parent.TryGetProperty("mainClass", out var pmc) ? pmc.GetString() ?? "" : "";
        o["mainClass"] = mainClass;

        // libraries: child first (patched forge libs win on dedup), then parent.
        var libs = new JsonArray();
        if (child.TryGetProperty("libraries", out var cl))
            foreach (var l in cl.EnumerateArray()) libs.Add(JsonNode.Parse(l.GetRawText()));
        if (parent.TryGetProperty("libraries", out var pl))
            foreach (var l in pl.EnumerateArray()) libs.Add(JsonNode.Parse(l.GetRawText()));
        o["libraries"] = libs;

        // Modern structured arguments: parent jvm/game, then child jvm/game.
        bool parentModern = parent.TryGetProperty("arguments", out var pa);
        bool childModern = child.TryGetProperty("arguments", out var ca);
        if (parentModern || childModern)
        {
            var args = new JsonObject();
            args["jvm"] = MergeArgArray(parentModern ? pa : default, childModern ? ca : default, "jvm");
            args["game"] = MergeArgArray(parentModern ? pa : default, childModern ? ca : default, "game");
            o["arguments"] = args;
        }
        // Legacy flat argument string (old Forge): child overrides parent.
        if (child.TryGetProperty("minecraftArguments", out var cma))
            o["minecraftArguments"] = cma.GetString();
        else if (parent.TryGetProperty("minecraftArguments", out var pma))
            o["minecraftArguments"] = pma.GetString();

        // Inherited-from-parent fields needed for install + launch.
        foreach (var key in new[] { "assetIndex", "assets", "downloads", "javaVersion", "logging" })
            if (parent.TryGetProperty(key, out var el))
                o[key] = JsonNode.Parse(el.GetRawText());

        return JsonDocument.Parse(o.ToJsonString());
    }

    static JsonArray MergeArgArray(JsonElement parentArgs, JsonElement childArgs, string which)
    {
        var arr = new JsonArray();
        void add(JsonElement args)
        {
            if (args.ValueKind == JsonValueKind.Object && args.TryGetProperty(which, out var a) && a.ValueKind == JsonValueKind.Array)
                foreach (var el in a.EnumerateArray()) arr.Add(JsonNode.Parse(el.GetRawText()));
        }
        add(parentArgs);
        add(childArgs);
        return arr;
    }

    // ---------------------------------------------------------------- version

    static async Task<JsonDocument> EnsureVersionJsonAsync(string versionId)
    {
        var path = VersionJsonPath(versionId);
        if (!File.Exists(path))
        {
            // Look the version up in the manifest to get its per-version json url.
            using var manifest = await GetJsonAsync(ManifestUrl);
            string? url = null;
            foreach (var v in manifest.RootElement.GetProperty("versions").EnumerateArray())
            {
                if (v.GetProperty("id").GetString() == versionId)
                {
                    url = v.GetProperty("url").GetString();
                    break;
                }
            }
            if (url == null) throw new Exception($"Версия {versionId} не найдена в манифесте Mojang.");
            var text = await WithRetry(() => Http.GetStringAsync(url));
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            await File.WriteAllTextAsync(path, text);
        }
        return JsonDocument.Parse(await File.ReadAllTextAsync(path));
    }

    // -------------------------------------------------------------- libraries

    static async Task<(List<string> classpath, List<string> natives)> DownloadLibrariesAsync(
        JsonElement root, Action<string, double> progress)
    {
        progress("libraries", 0);
        var classpath = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var natives = new List<string>();

        if (!root.TryGetProperty("libraries", out var libs))
        {
            progress("libraries", 1);
            return (classpath, natives);
        }

        var all = libs.EnumerateArray().ToList();
        for (int i = 0; i < all.Count; i++)
        {
            var lib = all[i];
            // Library-level rules gate (os/features).
            if (lib.TryGetProperty("rules", out var rules) && !RulesAllow(rules, null))
                { progress("libraries", (i + 1.0) / all.Count); continue; }

            var name = lib.GetProperty("name").GetString() ?? "";
            var parts = name.Split(':');
            var classifier = parts.Length >= 4 ? parts[3] : null;
            bool hasDownloads = lib.TryGetProperty("downloads", out var libDl);

            // Modern native artifacts carry the classifier in the maven name and
            // are separate entries whose artifact is the native jar itself.
            if (classifier != null && classifier.StartsWith("natives"))
            {
                if (IsWindowsNativeClassifier(classifier) && hasDownloads
                    && libDl.TryGetProperty("artifact", out var natArt))
                {
                    var p = Path.Combine(LibrariesDir, natArt.GetProperty("path").GetString()!);
                    await DownloadFileAsync(natArt.GetProperty("url").GetString()!, p, SizeOf(natArt));
                    natives.Add(p);
                }
                progress("libraries", (i + 1.0) / all.Count);
                continue;
            }

            // Normal library → classpath.
            if (hasDownloads && libDl.TryGetProperty("artifact", out var art))
            {
                var rel = art.GetProperty("path").GetString()!;
                var full = Path.Combine(LibrariesDir, rel);
                await DownloadFileAsync(art.GetProperty("url").GetString()!, full, SizeOf(art));
                if (seen.Add(rel)) classpath.Add(full);
            }
            else if (!hasDownloads && parts.Length >= 3)
            {
                // No downloads block: build the maven path/url ourselves.
                var rel = MavenPath(parts);
                var full = Path.Combine(LibrariesDir, rel);
                await DownloadFileAsync($"{LibrariesCdn}/{rel}", full, 0);
                if (seen.Add(rel)) classpath.Add(full);
            }

            // Old-style natives: a "natives" map + downloads.classifiers.
            if (lib.TryGetProperty("natives", out var natMap)
                && natMap.TryGetProperty("windows", out var winKey)
                && hasDownloads && libDl.TryGetProperty("classifiers", out var classifiers))
            {
                var key = winKey.GetString()!.Replace("${arch}", Environment.Is64BitOperatingSystem ? "64" : "32");
                if (classifiers.TryGetProperty(key, out var natArt))
                {
                    var p = Path.Combine(LibrariesDir, natArt.GetProperty("path").GetString()!);
                    await DownloadFileAsync(natArt.GetProperty("url").GetString()!, p, SizeOf(natArt));
                    natives.Add(p);
                }
            }

            progress("libraries", (i + 1.0) / all.Count);
        }
        progress("libraries", 1);
        return (classpath, natives);
    }

    static bool IsWindowsNativeClassifier(string c)
    {
        if (!c.Contains("windows")) return false;
        if (c.Contains("arm")) return false;                          // arm64 native
        if (c.Contains("x86") && !c.Contains("x86-64")) return false; // 32-bit native
        return true;
    }

    static string MavenPath(string[] parts)
    {
        // group:artifact:version[:classifier]
        var group = parts[0].Replace('.', '/');
        var artifact = parts[1];
        var version = parts[2];
        var suffix = parts.Length >= 4 ? "-" + parts[3] : "";
        return $"{group}/{artifact}/{version}/{artifact}-{version}{suffix}.jar";
    }

    static void ExtractNatives(List<string> nativeJars, string destDir)
    {
        Directory.CreateDirectory(destDir);
        foreach (var jar in nativeJars)
        {
            if (!File.Exists(jar)) continue;
            using var zip = ZipFile.OpenRead(jar);
            foreach (var entry in zip.Entries)
            {
                if (string.IsNullOrEmpty(entry.Name)) continue;                    // directory
                if (entry.FullName.StartsWith("META-INF", StringComparison.OrdinalIgnoreCase)) continue;
                var dest = Path.Combine(destDir, entry.Name);                       // flatten to dll/so name
                entry.ExtractToFile(dest, overwrite: true);
            }
        }
    }

    // ----------------------------------------------------------------- assets

    static async Task<string> DownloadAssetsAsync(JsonElement root, Action<string, double> progress)
    {
        progress("assets", 0);
        if (!root.TryGetProperty("assetIndex", out var idx)) { progress("assets", 1); return "legacy"; }

        var indexId = idx.GetProperty("id").GetString()!;
        var indexPath = Path.Combine(AssetsDir, "indexes", indexId + ".json");
        await DownloadFileAsync(idx.GetProperty("url").GetString()!, indexPath, SizeOf(idx));

        using var indexDoc = JsonDocument.Parse(await File.ReadAllTextAsync(indexPath));
        var indexRoot = indexDoc.RootElement;
        var objects = indexRoot.GetProperty("objects");

        // Pre-1.7 layouts serve loose files by name instead of hashed objects.
        bool mapToResources = indexRoot.TryGetProperty("map_to_resources", out var m) && m.GetBoolean();
        bool virtualAssets = indexRoot.TryGetProperty("virtual", out var vr) && vr.GetBoolean();

        var entries = objects.EnumerateObject().ToList();
        int total = entries.Count, done = 0;
        var objectsRoot = Path.Combine(AssetsDir, "objects");

        // Thousands of tiny files — bounded parallelism keeps it fast and polite.
        using var gate = new SemaphoreSlim(8);
        var tasks = new List<Task>();
        foreach (var e in entries)
        {
            await gate.WaitAsync();
            var name = e.Name;
            var hash = e.Value.GetProperty("hash").GetString()!;
            var size = e.Value.TryGetProperty("size", out var s) ? s.GetInt64() : 0;
            tasks.Add(Task.Run(async () =>
            {
                try
                {
                    var sub = hash.Substring(0, 2);
                    var objPath = Path.Combine(objectsRoot, sub, hash);
                    await DownloadFileAsync($"{AssetsCdn}/{sub}/{hash}", objPath, size);

                    // Legacy virtual/resource copies (name-addressed).
                    if (virtualAssets)
                        CopyTo(objPath, Path.Combine(AssetsDir, "virtual", indexId, name));
                    if (mapToResources)
                        CopyTo(objPath, Path.Combine(Config.McDir, "resources", name));
                }
                finally
                {
                    gate.Release();
                    var d = Interlocked.Increment(ref done);
                    progress("assets", total == 0 ? 1 : (double)d / total);
                }
            }));
        }
        await Task.WhenAll(tasks);
        progress("assets", 1);
        return indexId;
    }

    static void CopyTo(string src, string dest)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        if (!File.Exists(dest)) File.Copy(src, dest, overwrite: true);
    }

    // ------------------------------------------------------------------- java

    // Downloads the Mojang JRE matching the version's javaVersion.component and
    // returns the path to javaw.exe.
    static async Task<string> EnsureJavaAsync(JsonElement root, Action<string, double> progress)
    {
        progress("java", 0);
        var component = "jre-legacy";
        if (root.TryGetProperty("javaVersion", out var jv) && jv.TryGetProperty("component", out var c))
            component = c.GetString() ?? component;

        var componentDir = Path.Combine(RuntimeDir, component);

        // Reuse an already-installed runtime.
        var existing = FindJavaw(componentDir);
        if (existing != null) { progress("java", 1); return existing; }

        // all.json → windows-x64 → component → [0].manifest.url
        using var all = await GetJsonAsync(JavaRuntimesUrl);
        var platform = all.RootElement.GetProperty("windows-x64");
        if (!platform.TryGetProperty(component, out var arr) || arr.GetArrayLength() == 0)
            throw new Exception($"Java-рантайм '{component}' недоступен для windows-x64.");
        var manifestUrl = arr[0].GetProperty("manifest").GetProperty("url").GetString()!;

        using var manifest = await GetJsonAsync(manifestUrl);
        var files = manifest.RootElement.GetProperty("files");
        var fileEntries = files.EnumerateObject().ToList();
        int total = fileEntries.Count, done = 0;

        using var gate = new SemaphoreSlim(8);
        var tasks = new List<Task>();
        foreach (var f in fileEntries)
        {
            var relPath = f.Name;
            var el = f.Value;
            var type = el.GetProperty("type").GetString();
            var dest = Path.Combine(componentDir, relPath);

            if (type == "directory")
            {
                Directory.CreateDirectory(dest);
                Interlocked.Increment(ref done);
                continue;
            }
            if (type != "file") { Interlocked.Increment(ref done); continue; } // skip links (none on windows)

            if (!el.TryGetProperty("downloads", out var fdl) || !fdl.TryGetProperty("raw", out var raw))
                { Interlocked.Increment(ref done); continue; }
            var url = raw.GetProperty("url").GetString()!;
            var size = raw.TryGetProperty("size", out var sz) ? sz.GetInt64() : 0;

            await gate.WaitAsync();
            tasks.Add(Task.Run(async () =>
            {
                try { await DownloadFileAsync(url, dest, size); }
                finally
                {
                    gate.Release();
                    var d = Interlocked.Increment(ref done);
                    progress("java", total == 0 ? 1 : (double)d / total);
                }
            }));
        }
        await Task.WhenAll(tasks);

        var javaw = FindJavaw(componentDir)
            ?? throw new Exception("javaw.exe не найден в загруженном Java-рантайме.");
        progress("java", 1);
        return javaw;
    }

    static string? FindJavaw(string dir)
    {
        if (!Directory.Exists(dir)) return null;
        // Structure varies between runtimes — just locate the launcher binary.
        return Directory.EnumerateFiles(dir, "javaw.exe", SearchOption.AllDirectories).FirstOrDefault()
            ?? Directory.EnumerateFiles(dir, "java.exe", SearchOption.AllDirectories).FirstOrDefault();
    }

    // -------------------------------------------------------------- launching

    static ProcessStartInfo BuildStartInfo(
        JsonElement root, string versionId, McAccount account, int ramMb, string? serverIp,
        string javaw, List<string> classpath, string assetIndexId, string nativesVersionId)
    {
        var natives = NativesDir(nativesVersionId);
        var cp = string.Join(';', classpath);
        bool offline = account.Type == "offline" || string.IsNullOrEmpty(account.AccessToken);

        // Placeholder table shared by jvm + game argument templates.
        var subst = new Dictionary<string, string>
        {
            ["auth_player_name"] = account.Name,
            ["version_name"] = versionId,
            ["game_directory"] = Config.McDir,
            ["assets_root"] = AssetsDir,
            ["game_assets"] = Path.Combine(AssetsDir, "virtual", assetIndexId), // legacy
            ["assets_index_name"] = assetIndexId,
            ["auth_uuid"] = account.Uuid.Replace("-", ""),
            ["auth_access_token"] = offline ? "0" : account.AccessToken,
            ["auth_session"] = offline ? "0" : "token:" + account.AccessToken, // legacy
            ["clientid"] = Guid.NewGuid().ToString("N"),
            ["auth_xuid"] = "0",
            ["user_type"] = "msa",
            ["version_type"] = "release",
            ["natives_directory"] = natives,
            ["launcher_name"] = "PetusLauncher",
            ["launcher_version"] = "2.1",
            ["classpath"] = cp,
            ["library_directory"] = LibrariesDir,
            ["classpath_separator"] = ";",
            ["user_properties"] = "{}",
        };
        string Sub(string s)
        {
            foreach (var kv in subst) s = s.Replace("${" + kv.Key + "}", kv.Value);
            return s;
        }

        var jvmArgs = new List<string>();
        var gameArgs = new List<string>();
        bool quickPlay = !string.IsNullOrEmpty(serverIp);

        if (root.TryGetProperty("arguments", out var arguments))
        {
            // Modern (1.13+): structured jvm/game arrays with rule objects.
            if (arguments.TryGetProperty("jvm", out var jvm))
                ExpandArgs(jvm, Sub, jvmArgs, quickPlay);
            if (arguments.TryGetProperty("game", out var game))
                ExpandArgs(game, Sub, gameArgs, quickPlay);
        }
        else
        {
            // Legacy: build the jvm side ourselves; game args are a flat string.
            jvmArgs.Add("-Djava.library.path=" + natives);
            jvmArgs.Add("-cp");
            jvmArgs.Add(cp);
            var legacy = root.TryGetProperty("minecraftArguments", out var ma) ? ma.GetString() ?? "" : "";
            foreach (var tok in legacy.Split(' ', StringSplitOptions.RemoveEmptyEntries))
                gameArgs.Add(Sub(tok));
        }

        var psi = new ProcessStartInfo
        {
            FileName = javaw,
            WorkingDirectory = Config.McDir,
            UseShellExecute = false,
        };
        foreach (var a in jvmArgs) psi.ArgumentList.Add(a);
        psi.ArgumentList.Add($"-Xmx{ramMb}M");
        psi.ArgumentList.Add(root.GetProperty("mainClass").GetString()!);
        foreach (var a in gameArgs) psi.ArgumentList.Add(a);

        // Auto-join the server on modern versions.
        if (quickPlay)
        {
            psi.ArgumentList.Add("--quickPlayMultiplayer");
            psi.ArgumentList.Add(serverIp!);
        }
        return psi;
    }

    static void ExpandArgs(JsonElement arr, Func<string, string> sub, List<string> outList, bool quickPlay)
    {
        foreach (var el in arr.EnumerateArray())
        {
            if (el.ValueKind == JsonValueKind.String)
            {
                outList.Add(sub(el.GetString()!));
            }
            else if (el.ValueKind == JsonValueKind.Object)
            {
                if (el.TryGetProperty("rules", out var rules) && !RulesAllow(rules, quickPlay)) continue;
                var val = el.GetProperty("value");
                if (val.ValueKind == JsonValueKind.String)
                    outList.Add(sub(val.GetString()!));
                else if (val.ValueKind == JsonValueKind.Array)
                    foreach (var v in val.EnumerateArray()) outList.Add(sub(v.GetString()!));
            }
        }
    }

    // ------------------------------------------------------------------ rules

    // Evaluate a Mojang rules array for this machine (windows-x64). We treat all
    // feature flags as disabled — quick-play we append manually, and demo /
    // custom-resolution are never used — so feature-gated args are excluded.
    static bool RulesAllow(JsonElement rules, bool? quickPlay)
    {
        if (rules.ValueKind != JsonValueKind.Array || rules.GetArrayLength() == 0) return true;
        bool allow = false;
        foreach (var rule in rules.EnumerateArray())
        {
            var action = rule.GetProperty("action").GetString();
            bool applies = OsMatches(rule) && !HasFeatures(rule);
            if (applies) allow = action == "allow";
        }
        return allow;
    }

    static bool HasFeatures(JsonElement rule)
        => rule.TryGetProperty("features", out var f) && f.ValueKind == JsonValueKind.Object
           && f.EnumerateObject().Any();

    static bool OsMatches(JsonElement rule)
    {
        if (!rule.TryGetProperty("os", out var os)) return true;
        if (os.TryGetProperty("name", out var n))
        {
            var name = n.GetString();
            if (name != null && name != "windows") return false;
        }
        if (os.TryGetProperty("arch", out var a))
        {
            var arch = a.GetString();
            // Mojang uses "x86" to gate 32-bit; we are 64-bit so that never applies.
            if (arch == "x86" && Environment.Is64BitOperatingSystem) return false;
        }
        return true;
    }

    // ----------------------------------------------------------- io utilities

    static long SizeOf(JsonElement el)
        => el.TryGetProperty("size", out var s) ? s.GetInt64() : 0;

    static async Task<JsonDocument> GetJsonAsync(string url)
        => JsonDocument.Parse(await WithRetry(() => Http.GetStringAsync(url)));

    // Download url→dest, skipping when the file already exists with the expected
    // size (size 0 = unknown, always (re)download). Retries transient failures.
    static async Task DownloadFileAsync(string url, string dest, long size)
    {
        if (size > 0 && File.Exists(dest) && new FileInfo(dest).Length == size) return;
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        await WithRetry(async () =>
        {
            using var resp = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
            resp.EnsureSuccessStatusCode();
            var tmp = dest + ".part";
            await using (var src = await resp.Content.ReadAsStreamAsync())
            await using (var dst = File.Create(tmp))
                await src.CopyToAsync(dst);
            if (File.Exists(dest)) File.Delete(dest);
            File.Move(tmp, dest);
            return true;
        });
    }

    static async Task<T> WithRetry<T>(Func<Task<T>> fn, int tries = 3, int delayMs = 1500)
    {
        Exception? last = null;
        for (int i = 1; i <= tries; i++)
        {
            try { return await fn(); }
            catch (Exception e) { last = e; if (i < tries) await Task.Delay(delayMs); }
        }
        throw new Exception($"Загрузка не удалась после {tries} попыток: {last?.Message}");
    }
}
