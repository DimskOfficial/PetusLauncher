# Petus Connect

Клиентский Fabric-мод для PetusLauncher. Ничего не меняет в игре — только
отвечает серверу одноразовым тикетом PetusID, который выдал лаунчер.

## Протокол

1. Сервер (`petus-auth`) присылает `petus:challenge` с одноразовым nonce.
2. Мод отвечает `petus:ticket` (тикет + nonce + версия мода).
3. Сервер отвечает `petus:result` и либо пускает игрока, либо кикает.

Рукопожатие идёт в play-фазе, поэтому один и тот же код работает и за Velocity,
и на Paper/Folia, и не конфликтует с Sonar и LimboAuth.

## Где берётся тикет

По порядку: `-Dpetus.ticket`, переменная среды `PETUS_TICKET`,
`<instance>/petus/ticket.json`. Файл удаляется после чтения (single-use).

## Сборка

```bash
cd petus-connect
gradle build
```

Готовый артефакт: `build/libs/petus-connect-3.0.0.jar` — его раздаёт API через
`GET /api/files/petus-connect/latest` (`PETUS_CONNECT_URL`).
