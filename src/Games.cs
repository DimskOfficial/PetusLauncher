namespace PetusLauncher;

record ChangelogEntry(string Version, string Notes);

record GameDef(
    string Id,
    string Name,
    string Type,        // "gdps" | "mc"
    string Tagline,
    string? Ip,
    string Description,     // long store-page description
    bool HashCheck,        // integrity/hash verification available (GDPS only)
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
            "PetusGDPS — приватный сервер Geometry Dash 2.2 с собственным ядром, " +
            "рейтингами, уровнями и системой опыта Better Progression. В сборку " +
            "входят 23 мода: GMD/Editor API, QOLMod, загрузчик текстур и другое. " +
            "Вход только через Petus ID — пароли не нужны.",
            true,
            new[]
            {
                new ChangelogEntry("1.0.7", "Better Progression синхронизирует опыт с сервером Петус. Исправлены уровни и музыка."),
                new ChangelogEntry("1.0.6", "23 мода, вкладка Mods в Options, токен-авторизация."),
                new ChangelogEntry("1.0.1", "Приветственные текстбоксы, бонус новичкам."),
                new ChangelogEntry("1.0.0", "Первый релиз PetusGDPS."),
            }
        ),
        new GameDef(
            "petusmc", "PetusMC", "mc",
            "Анархический Minecraft-сервер экосистемы Петус",
            "mc.petus.ru",
            "PetusMC — анархический Minecraft-сервер. Античит практически " +
            "отсутствует, правил нет: читы разрешены, можно как угодно " +
            "модифицировать свой Minecraft — мы не проверяем хэши файлов. " +
            "Версия сервера — 1.21.11. Выбери версию клиента (1.21+), войди " +
            "через аккаунт Microsoft или как офлайн-игрок, и запускай прямо " +
            "из лаунчера.",
            false,
            new[]
            {
                new ChangelogEntry("live", "Сервер онлайн (1.21.11). Заходи по mc.petus.ru."),
            }
        ),
    };
}
