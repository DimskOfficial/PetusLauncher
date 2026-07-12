using System.IO.Compression;
using System.Text.Json;

namespace PetusLauncher;

record Manifest(string Version, string Url);

// Downloads + installs the game from the core manifest. Always sends a
// User-Agent (the CDN returns 403 without one) and retries transient failures.
static class Updater
{
    static readonly HttpClient Http = MakeClient();

    static HttpClient MakeClient()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("PetusLauncher/2.2");
        return c;
    }

    public static bool IsInstalled()
        => File.Exists(Path.Combine(Config.GameDir, Config.ExeName));

    public static string? InstalledVersion()
    {
        try
        {
            var doc = JsonDocument.Parse(File.ReadAllText(Config.VersionFile));
            return doc.RootElement.TryGetProperty("version", out var v) ? v.GetString() : null;
        }
        catch { return null; }
    }

    // True if a newer game build is available than what's installed.
    public static async Task<bool> UpdateAvailableAsync()
    {
        try
        {
            var m = await FetchManifestAsync();
            return IsInstalled() && InstalledVersion() != m.Version;
        }
        catch { return false; }
    }

    // Download size (Content-Length) of the current game zip — used to show the
    // install/update size without downloading. Returns 0 if unknown.
    public static async Task<long> RemoteSizeAsync()
    {
        try
        {
            var m = await FetchManifestAsync();
            using var req = new HttpRequestMessage(HttpMethod.Head, m.Url);
            using var resp = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
            return resp.Content.Headers.ContentLength ?? 0;
        }
        catch { return 0; }
    }

    static void WriteInstalled(string version)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Config.VersionFile)!);
        File.WriteAllText(Config.VersionFile,
            JsonSerializer.Serialize(new { version, updatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() }));
    }

    static async Task<T> WithRetry<T>(Func<Task<T>> fn, int tries = 5, int delayMs = 2000)
    {
        Exception? last = null;
        for (int i = 1; i <= tries; i++)
        {
            try { return await fn(); }
            catch (Exception e) { last = e; if (i < tries) await Task.Delay(delayMs); }
        }
        throw new Exception($"failed after {tries} tries: {last?.Message}");
    }

    public static async Task<Manifest> FetchManifestAsync()
    {
        return await WithRetry(async () =>
        {
            var json = await Http.GetStringAsync(Config.UpdateManifest);
            var m = JsonSerializer.Deserialize<Manifest>(json,
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
            if (m == null || string.IsNullOrEmpty(m.Url)) throw new Exception("bad manifest");
            return m;
        });
    }

    // Ensure the newest build is installed. `progress` reports stage + fraction.
    // Returns the installed version. Stages: check/download/install/uptodate/ready.
    public static async Task<string> EnsureUpToDateAsync(Action<string, double> progress)
    {
        progress("check", 0);
        Manifest manifest;
        try { manifest = await FetchManifestAsync(); }
        catch
        {
            var installed = InstalledVersion();
            if (installed != null) { progress("ready", 1); return installed; }
            throw;
        }

        if (InstalledVersion() == manifest.Version && IsInstalled())
        {
            progress("uptodate", 1);
            progress("ready", 1);
            return manifest.Version;
        }

        Directory.CreateDirectory(Config.GameDir);
        var tmpZip = Path.Combine(Config.GameDir, $".update-{manifest.Version}.zip");

        await WithRetry(async () =>
        {
            progress("download", 0);
            using var resp = await Http.GetAsync(manifest.Url, HttpCompletionOption.ResponseHeadersRead);
            resp.EnsureSuccessStatusCode();
            var total = resp.Content.Headers.ContentLength ?? 0;
            await using var src = await resp.Content.ReadAsStreamAsync();
            await using var dst = File.Create(tmpZip);
            var buffer = new byte[81920];
            long got = 0;
            int read;
            while ((read = await src.ReadAsync(buffer)) > 0)
            {
                await dst.WriteAsync(buffer.AsMemory(0, read));
                got += read;
                if (total > 0) progress("download", (double)got / total);
            }
            return true;
        });

        progress("install", 0);
        // Extract over the game dir (overwrite).
        using (var archive = ZipFile.OpenRead(tmpZip))
        {
            foreach (var entry in archive.Entries)
            {
                if (string.IsNullOrEmpty(entry.Name)) continue; // directory
                var dest = Path.Combine(Config.GameDir, entry.FullName);
                Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                entry.ExtractToFile(dest, overwrite: true);
            }
        }
        try { File.Delete(tmpZip); } catch { }

        WriteInstalled(manifest.Version);
        progress("ready", 1);
        return manifest.Version;
    }
}
