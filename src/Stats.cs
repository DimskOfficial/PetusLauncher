using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace PetusLauncher;

record GameStat(long PlaySeconds, long LastPlayed, long SizeBytes);

record IntegrityResult(bool Ok, List<string> Changed, bool NoSnapshot);

static class Stats
{
    static JsonObject Read(string file)
    {
        try { return JsonNode.Parse(File.ReadAllText(file)) as JsonObject ?? new JsonObject(); }
        catch { return new JsonObject(); }
    }
    static void Write(string file, JsonObject obj)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        File.WriteAllText(file, obj.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
    }

    public static GameStat Get(string gameId)
    {
        var all = Read(Config.StatsFile);
        if (all[gameId] is JsonObject s)
            return new GameStat(
                (long?)s["playSeconds"] ?? 0,
                (long?)s["lastPlayed"] ?? 0,
                (long?)s["sizeBytes"] ?? 0);
        return new GameStat(0, 0, 0);
    }

    static void Set(string gameId, GameStat st)
    {
        var all = Read(Config.StatsFile);
        all[gameId] = new JsonObject
        {
            ["playSeconds"] = st.PlaySeconds,
            ["lastPlayed"] = st.LastPlayed,
            ["sizeBytes"] = st.SizeBytes,
        };
        Write(Config.StatsFile, all);
    }

    public static void AddPlaytime(string gameId, double seconds, long launchedAt)
    {
        var s = Get(gameId);
        Set(gameId, s with { PlaySeconds = s.PlaySeconds + (long)Math.Max(0, Math.Round(seconds)), LastPlayed = launchedAt });
    }

    public static void SetLastPlayed(string gameId, long when)
    {
        var s = Get(gameId);
        Set(gameId, s with { LastPlayed = when });
    }

    public static long UpdateSize(string gameId, string dir)
    {
        long size = DirSize(dir);
        var s = Get(gameId);
        Set(gameId, s with { SizeBytes = size });
        return size;
    }

    static long DirSize(string dir)
    {
        long total = 0;
        if (!Directory.Exists(dir)) return 0;
        try
        {
            foreach (var f in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories))
            {
                try { total += new FileInfo(f).Length; } catch { }
            }
        }
        catch { }
        return total;
    }

    // ---- integrity: sha256 of exe + every .geode mod ----
    static IEnumerable<string> KeyFiles(string gameDir)
    {
        yield return Path.Combine(gameDir, Config.ExeName);
        var mods = Path.Combine(gameDir, "geode", "mods");
        if (Directory.Exists(mods))
            foreach (var f in Directory.EnumerateFiles(mods, "*.geode"))
                yield return f;
    }

    static string Hash(string file)
    {
        using var sha = SHA256.Create();
        using var fs = File.OpenRead(file);
        return Convert.ToHexString(sha.ComputeHash(fs));
    }

    public static void Snapshot(string gameId, string gameDir)
    {
        var map = new JsonObject();
        foreach (var f in KeyFiles(gameDir))
        {
            try { map[Path.GetRelativePath(gameDir, f)] = Hash(f); } catch { }
        }
        var all = Read(Config.IntegrityFile);
        all[gameId] = map;
        Write(Config.IntegrityFile, all);
    }

    public static IntegrityResult Verify(string gameId, string gameDir)
    {
        return VerifyWithProgress(gameId, gameDir, null);
    }

    // Same as Verify but calls `progress(done, total)` as it hashes each file so
    // the UI can drive a progress bar.
    public static IntegrityResult VerifyWithProgress(string gameId, string gameDir, Action<int, int>? progress)
    {
        var all = Read(Config.IntegrityFile);
        if (all[gameId] is not JsonObject snap)
            return new IntegrityResult(true, new(), true);

        var changed = new List<string>();
        int total = snap.Count, done = 0;
        progress?.Invoke(0, total);
        foreach (var kv in snap)
        {
            var rel = kv.Key;
            var expected = kv.Value?.GetValue<string>();
            var abs = Path.Combine(gameDir, rel);
            string? actual = null;
            try { actual = Hash(abs); } catch { }
            if (actual != expected) changed.Add(rel);
            done++;
            progress?.Invoke(done, total);
        }
        return new IntegrityResult(changed.Count == 0, changed, false);
    }
}
