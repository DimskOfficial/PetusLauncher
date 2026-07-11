# PetusLauncher

Десктопный лаунчер экосистемы **Петус** (Windows, .NET 8 / WinForms).
Вход через Petus ID, установка и авто-обновление PetusGDPS, запуск игры уже
авторизованным. Оформление — в стиле ВК-2010.

## Возможности
- **Вход через Petus ID** — открывается системный браузер (где ты уже вошёл),
  токен возвращается на localhost. Пароли не вводятся.
- **Каталог игр** (сайдбар): PetusGDPS и PetusMC. Страницы в стиле Steam с
  баннером, статистикой и обновлениями.
- **PetusGDPS**: «Установить/Играть», прогресс загрузки, авто-обновление из
  манифеста ядра (`/api/game/manifest`).
- **PetusMC**: онлайн сервера (players online) + копирование IP `mc.petus.ru`.
- **Статистика игры**: наиграно, размер установки, последний запуск.
- **Целостность**: sha256-снимок exe + модов после установки; кнопка
  «Проверить целостность» ловит подмену файлов.
- **Меню профиля** по клику на ник: Профиль / Настройки / Выйти.

## Сборка
```
dotnet build PetusLauncher.csproj -c Release
```
Self-contained single-file exe (без нужды в .NET у пользователя):
```
dotnet publish PetusLauncher.csproj -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o publish
```

## Конфигурация (env, необязательно)
- `PETUS_SITE_URL` — сайт (по умолчанию `https://gdps.petus.ru`)
- `PETUS_CORE_URL` — ядро (по умолчанию `https://cgdps.petus.ru`)
- `PETUS_CDN_URL` — CDN (по умолчанию `https://cdn.petus.goonhost.rocks`)

## Публикация на S3
`publish.mjs` — SigV4-загрузчик (без AWS CLI):
```
AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... \
  node publish.mjs put publish/PetusLauncher.exe launcher/PetusLauncher-<ver>.exe application/octet-stream
```

## Структура
- `src/Program.cs` — точка входа
- `src/MainForm*.cs` — UI (title bar, сайдбар, страницы, модалки)
- `src/Auth.cs` — вход через браузер + loopback
- `src/Updater.cs` — манифест + загрузка/распаковка игры (User-Agent + ретраи)
- `src/Stats.cs` — статистика + проверка целостности
- `src/GameLauncher.cs` — запуск игры, учёт времени
- `src/Games.cs`, `src/Config.cs`, `src/Theme.cs`
