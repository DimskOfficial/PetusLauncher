using System.Diagnostics;
using System.Text.Json;

namespace PetusLauncher;

static class GameLauncher
{
    public static event Action<string>? GameClosed;

    // Write the launcher session (token) where the mod reads it, then spawn GD
    // and track playtime until it exits.
    public static void Launch(AuthData auth)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Config.TokenFile)!);
        var payload = new Dictionary<string, object>
        {
            ["token"] = auth.Token,
            ["name"] = auth.Name,
            ["account"] = auth.Account,
            ["ts"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };
        File.WriteAllText(Config.TokenFile, JsonSerializer.Serialize(payload));

        var exe = Path.Combine(Config.GameDir, Config.ExeName);
        if (!File.Exists(exe)) throw new Exception("Игра не установлена.");

        long startedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        Stats.SetLastPlayed("petusgdps", startedAt);

        var proc = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = exe,
                WorkingDirectory = Config.GameDir,
                UseShellExecute = false,
            },
            EnableRaisingEvents = true,
        };
        proc.Exited += (_, _) =>
        {
            double secs = (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - startedAt) / 1000.0;
            Stats.AddPlaytime("petusgdps", secs, startedAt);
            GameClosed?.Invoke("petusgdps");
            proc.Dispose();
        };
        proc.Start();
    }
}
