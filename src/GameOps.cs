using System.Diagnostics;
using System.IO.Compression;

namespace PetusLauncher;

// Per-game management operations shared by the right-click menu and the
// Properties window: locate the install dir, open files, delete, desktop
// shortcut, backup. Kept UI-free so callers own their own dialogs.
static class GameOps
{
    // Install directory for a game (GDPS and MC live in different roots).
    public static string InstallDir(GameDef g) => g.Type == "mc" ? Config.McDir : Config.GameDir;

    public static bool IsInstalled(GameDef g) =>
        g.Type == "mc" ? McInstalled() : Updater.IsInstalled();

    // MC counts as installed only if a real client version jar exists (not just
    // leftover runtime/assets dirs from a Java download). Fixes the bug where
    // it still reported "installed" after "Удалить с устройства".
    public static bool McInstalled()
    {
        var versions = Path.Combine(Config.McDir, "versions");
        if (!Directory.Exists(versions)) return false;
        try
        {
            foreach (var dir in Directory.EnumerateDirectories(versions))
            {
                var id = Path.GetFileName(dir);
                if (File.Exists(Path.Combine(dir, id + ".jar"))) return true;
            }
        }
        catch { }
        return false;
    }

    public static void OpenFolder(GameDef g)
    {
        var dir = InstallDir(g);
        if (Directory.Exists(dir)) Process.Start("explorer.exe", dir);
    }

    // Open a named subfolder (logs / crashlogs / geode) creating it if needed.
    public static void OpenSubfolder(GameDef g, string sub)
    {
        var dir = Path.Combine(InstallDir(g), sub);
        Directory.CreateDirectory(dir);
        Process.Start("explorer.exe", dir);
    }

    public static void DeleteInstall(GameDef g)
    {
        var dir = InstallDir(g);
        if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
    }

    // Create a desktop shortcut that re-opens the launcher on this game.
    // Uses a .url internet shortcut to the launcher exe (no COM dependency).
    public static string CreateDesktopShortcut(GameDef g)
    {
        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        var exe = Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "PetusLauncher.exe");
        var lnk = Path.Combine(desktop, $"{g.Name}.url");
        // .url with a custom launch arg so the launcher opens straight on the game.
        File.WriteAllText(lnk,
            "[InternetShortcut]\r\n" +
            $"URL=file:///{exe.Replace('\\', '/')}\r\n" +
            $"IconIndex=0\r\n" +
            $"IconFile={exe}\r\n");
        return lnk;
    }

    // Zip the install dir into the user's Documents\PetusBackups. Returns the path.
    public static string Backup(GameDef g)
    {
        var dir = InstallDir(g);
        if (!Directory.Exists(dir)) throw new Exception("Игра не установлена.");
        var backups = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "PetusBackups");
        Directory.CreateDirectory(backups);
        var stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
        var zip = Path.Combine(backups, $"{g.Id}-{stamp}.zip");
        ZipFile.CreateFromDirectory(dir, zip, CompressionLevel.Fastest, includeBaseDirectory: false);
        return zip;
    }
}
