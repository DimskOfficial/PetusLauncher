namespace PetusLauncher;

// Central configuration. Env overrides allowed at runtime; sane production
// defaults point at the live Petus ecosystem.
static class Config
{
    public static string Site => Env("PETUS_SITE_URL", "https://gdps.petus.ru");
    public static string Cdn => Env("PETUS_CDN_URL", "https://cdn.petus.goonhost.rocks");
    public static string Core => Env("PETUS_CORE_URL", "https://cgdps.petus.ru");

    // Website that performs the Petus ID OAuth handoff (opened in the browser).
    public static string HandoffUrl => $"{Site}/api/launcher/handoff";

    // Game update manifest served by the core (Kestrel, no CDN cache).
    public static string UpdateManifest => $"{Core}/api/game/manifest";

    // Launcher self-update manifest (checked on startup).
    public static string LauncherManifest => $"{Core}/api/launcher/version";

    static string LocalAppData => Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);

    // Per-user install locations (no admin rights needed).
    public static string GameDir => Path.Combine(LocalAppData, "PetusGDPS", "game");
    public static string VersionFile => Path.Combine(LocalAppData, "PetusGDPS", "installed.json");
    public static string TokenFile => Path.Combine(LocalAppData, "PetusGDPS", "session.json");
    public static string ExeName => "GeometryDash.exe";

    // --- PetusMC (Minecraft) ---
    // Standard .minecraft layout so vanilla assets/versions/libraries are reused
    // if the player already has Minecraft installed elsewhere is avoided; we keep
    // our own isolated instance under PetusMC to not disturb their vanilla game.
    public static string McDir => Path.Combine(LocalAppData, "PetusMC");
    public static string McServerIp => Env("PETUS_MC_IP", "mc.petus.ru");
    public static string McServerVersion => Env("PETUS_MC_SERVER_VERSION", "1.21.11");
    // Microsoft OAuth (public client id for Minecraft; device-code flow).
    public static string MsClientId => Env("PETUS_MS_CLIENT_ID", "00000000402b5328"); // MC vanilla launcher public client


    // Launcher's own data dir (auth, stats, integrity).
    public static string DataDir => Path.Combine(LocalAppData, "PetusLauncher");
    public static string AuthFile => Path.Combine(DataDir, "auth.json");
    public static string StatsFile => Path.Combine(DataDir, "stats.json");
    public static string IntegrityFile => Path.Combine(DataDir, "integrity.json");

    static string Env(string name, string fallback)
    {
        var v = Environment.GetEnvironmentVariable(name);
        return string.IsNullOrWhiteSpace(v) ? fallback : v;
    }
}
