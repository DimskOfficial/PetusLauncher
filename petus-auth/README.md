# petus-auth

Серверная часть входа через PetusLauncher. Две сборки из одного кода:

| Модуль | Артефакт | Где ставится |
| --- | --- | --- |
| `velocity` | `petus-auth-velocity-3.0.0.jar` | прокси PetusMC (Velocity + Sonar + LimboAuth) |
| `paper` | `petus-auth-paper-3.0.0.jar` | PetusCreate (Paper / Folia) |
| `common` | — | проверка тикета, конфиг, анти-реплей |

## Сборка

```bash
cd petus-auth
gradle build
```

## Как работает

1. Игрок зашёл на сервер → плагин присылает `petus:challenge` с nonce.
2. `petus-connect` отвечает `petus:ticket` с тикетом PetusID.
3. Плагин проверяет подпись HS256 (`TICKET_SECRET`) и/или стучится в
   `POST /api/game/verify` с `X-Service-Secret`.
4. Успешно → LimboAuth больше не спрашивает пароль, Sonar считает адрес
   проверенным. Нет тикета и `mode: require` → кик с ссылкой на лаунчер.

Анти-реплей: каждый `jti` принимается один раз; nonce связывает ответ с
конкретным подключением, а клиентские `petus:*` сообщения никогда не
пересылаются на бэкенд.

## Конфигурация

`plugins/petus-auth/config.json` (один и тот же формат на обеих платформах):

- `serverId` — `petusmc` или `petuscreate`;
- `mode` — `require` (только лаунчер, для PetusCreate) или `optional`
  (можно без лаунчера, для PetusMC);
- `handshake` — таймауты, повторы, минимальный протокол мода;
- `verification` — offline-секрет, remote-проверка, fail-open, анти-реплей;
- `identity` — сверка ника/UUID с аккаунтом PetusID;
- `bypass` — права, ники, UUID и IP без проверки;
- `integrations` — LimboAuth, Sonar, Floodgate;
- `messages` — все тексты (MiniMessage);
- `logging` — подробность логов.

## Команды

- `/petusauth status` — режим, сколько игроков вошли через лаунчер;
- `/petusauth reload` — перечитать конфиг (`petus.auth.reload`);
- `/petusauth whois <ник>` — какой аккаунт PetusID за игроком.
