namespace PetusLauncher;

record ChangelogEntry(string Version, string Notes);

record GameDef(
    string Id,
    string Name,
    string Type,        // "gdps" | "mc"
    string Tagline,
    string? Ip,
    ChangelogEntry[] Changelog
);

static class Games
{
    public static readonly GameDef[] All =
    {
        new GameDef(
            "petusgdps", "PetusGDPS", "gdps",
            "Приватный сервер Geometry Dash 2.2",
            null,
            new[]
            {
                new ChangelogEntry("1.0.3", "21 модов: GMD/Editor API, QoL, текстуры. Вкладка Mods в Options."),
                new ChangelogEntry("1.0.1", "Приветственные текстбоксы, бонус новичкам."),
                new ChangelogEntry("1.0.0", "Первый релиз PetusGDPS."),
            }
        ),
        new GameDef(
            "petusmc", "PetusMC", "mc",
            "Minecraft-сервер экосистемы Петус",
            "mc.petus.ru",
            new[]
            {
                new ChangelogEntry("live", "Сервер онлайн. Заходи по mc.petus.ru."),
            }
        ),
    };
}
