# PetusLauncher

VK-styled Electron launcher for **PetusGDPS**.

- **Вход только через Petus ID** — никаких логинов/паролей в игре или в лаунчере.
- **Автообновление** — при запуске проверяет `version.json` на CDN (S3), скачивает
  и распаковывает новую сборку без действий пользователя.
- **Запуск игры авторизованным** — токен пишется в `session.json`, мод его читает.

## Как это работает

```
[Launcher]  →  открывает  gdps.petus.ru/api/launcher/handoff  (встроенное окно)
            →  пользователь входит через Petus ID (обычный OAuth сайта)
            →  сайт минтит игровой токен и редиректит на
                 petus-launcher://auth?token=...&name=...&account=...
[Launcher]  ←  перехватывает редирект, сохраняет токен
            →  ensureUpToDate(): version.json → скачать zip → распаковать в gameDir
            →  пишет session.json  →  запускает GeometryDash.exe
[Mod]       →  читает session.json, логинит игрока, отключает вход в игре
```

Секрет OAuth и `PETUS_API_SECRET` **никогда** не попадают в клиент — токен
минтит сайт на сервере.

## Разработка

```bash
npm install
npm start          # запустить лаунчер локально (electron .)
npm run dist       # собрать инсталлятор (dist/PetusLauncher-Setup.exe)
```

Переопределяемые переменные окружения (build-time):

| Переменная         | По умолчанию                          |
| ------------------ | ------------------------------------- |
| `PETUS_SITE_URL`   | `https://gdps.petus.ru`               |
| `PETUS_CDN_URL`    | `https://cdn.petus.goonhost.rocks`    |

## Формат манифеста обновления (`<CDN>/game/version.json`)

```json
{
  "version": "1.0.0",
  "url": "https://cdn.petus.goonhost.rocks/game/petusgdps-1.0.0.zip"
}
```

`url` — zip с содержимым игры (в корне архива должен лежать `GeometryDash.exe`
и мод). Лаунчер распаковывает его в
`%LOCALAPPDATA%\PetusGDPS\game`.

## Публикация

1. Соберите игру + мод в zip, залейте на S3: `game/petusgdps-<ver>.zip`.
2. Обновите `game/version.json` (version + url).
3. Соберите инсталлятор (`npm run dist`) и залейте
   `dist/PetusLauncher-Setup.exe` → `launcher/PetusLauncher-Setup.exe`.
   Сайт уже ссылается на него кнопкой «Скачать».
