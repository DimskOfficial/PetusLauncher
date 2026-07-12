using System.Diagnostics;
using System.Reflection;
using System.Text.Json;

namespace PetusLauncher;

// Launcher self-update. On startup we ask the core for the latest launcher
// version; if it's newer than ours we download the new exe next to the current
// one and hand off to a tiny batch script that waits for us to exit, swaps the
// exe, and relaunches. Single-file publish means the whole app is one .exe.
static class SelfUpdate
{
    static readonly HttpClient Http = MakeClient();
    static HttpClient MakeClient()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("PetusLauncher/2.2");
        return c;
    }

    public static string CurrentVersion =>
        Assembly.GetExecutingAssembly().GetName().Version is { } v ? $"{v.Major}.{v.Minor}.{v.Build}" : "2.2.0";

    public record Info(string Version, string Url);

    public static async Task<Info?> CheckAsync()
    {
        try
        {
            var json = await Http.GetStringAsync(Config.LauncherManifest);
            var doc = JsonDocument.Parse(json).RootElement;
            var version = doc.TryGetProperty("version", out var v) ? v.GetString() : null;
            var url = doc.TryGetProperty("url", out var u) ? u.GetString() : null;
            if (string.IsNullOrEmpty(version) || string.IsNullOrEmpty(url)) return null;
            return new Info(version, url);
        }
        catch { return null; }
    }

    public static bool IsNewer(string remote) => CompareVersions(remote, CurrentVersion) > 0;

    static int CompareVersions(string a, string b)
    {
        int[] pa = Parse(a), pb = Parse(b);
        for (int i = 0; i < 3; i++) { if (pa[i] != pb[i]) return pa[i].CompareTo(pb[i]); }
        return 0;
    }
    static int[] Parse(string s)
    {
        var parts = s.TrimStart('v').Split('.');
        var r = new int[3];
        for (int i = 0; i < 3 && i < parts.Length; i++) int.TryParse(parts[i], out r[i]);
        return r;
    }

    // Download the new exe and relaunch through a swap script. Reports progress.
    public static async Task DownloadAndApplyAsync(Info info, Action<double> progress)
    {
        var current = Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "PetusLauncher.exe");
        var dir = Path.GetDirectoryName(current)!;
        var newExe = Path.Combine(dir, "PetusLauncher.new.exe");

        using (var resp = await Http.GetAsync(info.Url, HttpCompletionOption.ResponseHeadersRead))
        {
            resp.EnsureSuccessStatusCode();
            var total = resp.Content.Headers.ContentLength ?? 0;
            await using var src = await resp.Content.ReadAsStreamAsync();
            await using var dst = File.Create(newExe);
            var buf = new byte[81920];
            long got = 0; int read;
            while ((read = await src.ReadAsync(buf)) > 0)
            {
                await dst.WriteAsync(buf.AsMemory(0, read));
                got += read;
                if (total > 0) progress((double)got / total);
            }
        }

        // Batch: wait for this process to exit, replace the exe, relaunch, self-delete.
        var bat = Path.Combine(dir, "petus_update.bat");
        File.WriteAllText(bat,
            "@echo off\r\n" +
            "timeout /t 2 /nobreak >nul\r\n" +
            $":retry\r\n" +
            $"del \"{current}\" >nul 2>&1\r\n" +
            $"if exist \"{current}\" ( timeout /t 1 /nobreak >nul & goto retry )\r\n" +
            $"move /y \"{newExe}\" \"{current}\" >nul\r\n" +
            $"start \"\" \"{current}\"\r\n" +
            "del \"%~f0\"\r\n");

        Process.Start(new ProcessStartInfo
        {
            FileName = "cmd.exe",
            Arguments = $"/c \"{bat}\"",
            CreateNoWindow = true,
            UseShellExecute = false,
        });
    }
}
