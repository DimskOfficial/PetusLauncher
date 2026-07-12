using System.Text.Json;
using System.Text.Json.Nodes;

namespace PetusLauncher;

// Persisted launcher preferences + Minecraft account store. Small JSON file in
// the launcher data dir. Theme switching and the saved MC login live here.
static class LauncherSettings
{
    public static string File => Path.Combine(Config.DataDir, "settings.json");

    static JsonObject Read()
    {
        try { return JsonNode.Parse(System.IO.File.ReadAllText(File)) as JsonObject ?? new JsonObject(); }
        catch { return new JsonObject(); }
    }
    static void Write(JsonObject o)
    {
        Directory.CreateDirectory(Config.DataDir);
        System.IO.File.WriteAllText(File, o.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
    }

    // ---- theme ("light" | "dark") ----
    public static string ThemeName
    {
        get => (Read()["theme"]?.GetValue<string>()) ?? "light";
        set { var o = Read(); o["theme"] = value; Write(o); }
    }

    // ---- selected Minecraft version ----
    public static string McVersion
    {
        get => (Read()["mcVersion"]?.GetValue<string>()) ?? "";
        set { var o = Read(); o["mcVersion"] = value; Write(o); }
    }

    // ---- Java max heap (MB) ----
    public static int McRamMb
    {
        get => (int?)(Read()["mcRamMb"]) ?? 2048;
        set { var o = Read(); o["mcRamMb"] = value; Write(o); }
    }

    // ---- saved Minecraft account (persisted between runs) ----
    public static McAccount? McAccount
    {
        get
        {
            if (Read()["mcAccount"] is not JsonObject a) return null;
            try
            {
                return new McAccount(
                    a["name"]?.GetValue<string>() ?? "Player",
                    a["uuid"]?.GetValue<string>() ?? "",
                    a["accessToken"]?.GetValue<string>() ?? "",
                    a["type"]?.GetValue<string>() ?? "offline",
                    (long?)a["refreshExpires"] ?? 0,
                    a["refreshToken"]?.GetValue<string>() ?? "");
            }
            catch { return null; }
        }
        set
        {
            var o = Read();
            if (value == null) { o.Remove("mcAccount"); }
            else
            {
                o["mcAccount"] = new JsonObject
                {
                    ["name"] = value.Name,
                    ["uuid"] = value.Uuid,
                    ["accessToken"] = value.AccessToken,
                    ["type"] = value.Type,
                    ["refreshExpires"] = value.RefreshExpires,
                    ["refreshToken"] = value.RefreshToken,
                };
            }
            Write(o);
        }
    }
}

// A Minecraft account (Microsoft or offline). AccessToken is empty/placeholder
// for offline accounts. RefreshToken lets us silently re-auth Microsoft logins.
record McAccount(
    string Name,
    string Uuid,
    string AccessToken,
    string Type,            // "microsoft" | "offline"
    long RefreshExpires,    // unix seconds; 0 for offline
    string RefreshToken);
