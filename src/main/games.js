// Catalogue of games shown in the launcher sidebar. Each entry drives its
// store-style page. 'gdps' games are installed/updated + launched by the
// launcher; 'mc' games just show a joinable server IP.
'use strict';

const CORE = process.env.PETUS_CORE_URL || 'https://cgdps.petus.ru';

module.exports = [
  {
    id: 'petusgdps',
    name: 'PetusGDPS',
    type: 'gdps',
    tagline: 'Приватный сервер Geometry Dash 2.2',
    // Manifest served by the core (Kestrel, no CDN cache).
    updateManifest: `${CORE}/api/game/manifest`,
    // Banner/logo are bundled assets (fall back to a gradient if missing).
    banner: 'assets/banner-gdps.png',
    icon: 'assets/icon.png',
    changelog: [
      { version: '1.0.3', notes: '21 модов: GMD/Editor API, QoL, текстуры. Вкладка Mods в Options.' },
      { version: '1.0.1', notes: 'Приветственные текстбоксы, бонус новичкам.' },
      { version: '1.0.0', notes: 'Первый релиз PetusGDPS.' },
    ],
  },
  {
    id: 'petusmc',
    name: 'PetusMC',
    type: 'mc',
    tagline: 'Minecraft-сервер экосистемы Петус',
    ip: 'mc.petus.ru',
    banner: 'assets/banner-mc.png',
    icon: 'assets/icon.png',
    changelog: [
      { version: 'live', notes: 'Сервер онлайн. Заходи по mc.petus.ru.' },
    ],
  },
];
