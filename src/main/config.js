// Central config for the launcher. All URLs are overridable via env at build
// time; sane production defaults point at the live Petus ecosystem.

'use strict';
const path = require('path');
const os = require('os');

const SITE = process.env.PETUS_SITE_URL || 'https://gdps.petus.ru';
const CDN = process.env.PETUS_CDN_URL || 'https://cdn.petus.goonhost.rocks';
const CORE = process.env.PETUS_CORE_URL || 'https://cgdps.petus.ru';

module.exports = {
  // Website that performs the Petus ID OAuth handoff.
  site: SITE,
  handoffUrl: `${SITE}/api/launcher/handoff`,

  // Auto-update: the manifest is served by the CORE (Kestrel, no CDN cache) so
  // a freshly published build is seen immediately; the game .zip it points to
  // is a versioned, cache-proof CDN URL.
  updateManifest: `${CORE}/api/game/manifest`,

  // Where the game gets installed (per-user, no admin rights needed).
  gameDir: path.join(os.homedir(), 'AppData', 'Local', 'PetusGDPS', 'game'),
  // File that records the currently installed game version.
  versionFile: path.join(os.homedir(), 'AppData', 'Local', 'PetusGDPS', 'installed.json'),
  // File used to hand the auth token to the mod (read once on game start).
  tokenFile: path.join(os.homedir(), 'AppData', 'Local', 'PetusGDPS', 'session.json'),

  // Game executable inside gameDir.
  exeName: 'GeometryDash.exe',

  protocol: 'petus-launcher',
  // Deep link used by the site "Play" button: petusgdps://play?level=<id>.
  gameProtocol: 'petusgdps',
};
