'use strict';

const $ = (id) => document.getElementById(id);
const el = (tag, cls, html) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (html != null) n.innerHTML = html;
  return n;
};

let GAMES = [];
let currentGameId = null;
let authed = null;

// ---- window controls + user menu -----------------------------------------
$('winMin').addEventListener('click', () => window.petus.minimize());
$('winClose').addEventListener('click', () => window.petus.close());

const userChip = $('userChip');
const userMenu = $('userMenu');
$('userName') &&
  userChip.addEventListener('click', (e) => {
    e.stopPropagation();
    userMenu.hidden = !userMenu.hidden;
  });
document.addEventListener('click', () => (userMenu.hidden = true));
userMenu.addEventListener('click', async (e) => {
  const act = e.target?.dataset?.act;
  if (!act) return;
  e.preventDefault();
  userMenu.hidden = true;
  const name = authed?.name || '';
  if (act === 'profile') window.petus.openExternal(`https://gdps.petus.ru/u/${encodeURIComponent(name)}`);
  else if (act === 'settings') window.petus.openExternal('https://gdps.petus.ru/dashboard');
  else if (act === 'logout') {
    await window.petus.logout();
    authed = null;
    renderAuthState();
  }
});

// ---- modal ----------------------------------------------------------------
function openModal(title, bodyNode) {
  $('modalTitle').textContent = title;
  const body = $('modalBody');
  body.innerHTML = '';
  body.appendChild(bodyNode);
  $('modalWrap').hidden = false;
}
$('modalClose').addEventListener('click', () => ($('modalWrap').hidden = true));
$('modalWrap').addEventListener('click', (e) => {
  if (e.target === $('modalWrap')) $('modalWrap').hidden = true;
});

// ---- helpers --------------------------------------------------------------
function fmtDuration(sec) {
  if (!sec) return '0 мин';
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (h > 0) return `${h} ч ${m} мин`;
  return `${m} мин`;
}
function fmtSize(bytes) {
  if (!bytes) return '—';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1024) return (mb / 1024).toFixed(2) + ' ГБ';
  return mb.toFixed(0) + ' МБ';
}
function fmtDate(ts) {
  if (!ts) return 'никогда';
  try {
    return new Date(ts).toLocaleString('ru-RU');
  } catch {
    return '—';
  }
}

// ---- auth state -----------------------------------------------------------
function renderAuthState() {
  const loginView = $('loginView');
  const gameView = $('gameView');
  if (authed && authed.token) {
    userChip.hidden = false;
    $('userName').textContent = authed.name || 'Player';
    loginView.hidden = true;
    gameView.hidden = false;
    if (!currentGameId && GAMES.length) selectGame(GAMES[0].id);
    else if (currentGameId) selectGame(currentGameId);
  } else {
    userChip.hidden = true;
    gameView.hidden = true;
    loginView.hidden = false;
  }
}

$('loginBtn').addEventListener('click', async () => {
  $('loginBtn').disabled = true;
  try {
    authed = await window.petus.login();
    renderAuthState();
  } catch (e) {
    if (e && e.message !== 'cancelled') alert('Не удалось войти: ' + (e.message || e));
  } finally {
    $('loginBtn').disabled = false;
  }
});

// ---- sidebar --------------------------------------------------------------
function renderSidebar() {
  const list = $('gameList');
  list.innerHTML = '';
  for (const g of GAMES) {
    const item = el('button', 'game-item' + (g.id === currentGameId ? ' active' : ''));
    item.innerHTML = `<span class="gi-dot ${g.type}"></span><span class="gi-name">${g.name}</span>`;
    item.addEventListener('click', () => selectGame(g.id));
    list.appendChild(item);
  }
}

// ---- game page ------------------------------------------------------------
async function selectGame(id) {
  currentGameId = id;
  renderSidebar();
  const g = GAMES.find((x) => x.id === id);
  if (!g) return;
  const view = $('gameView');
  view.innerHTML = '';

  // Banner
  const banner = el('div', `banner banner-${g.type}`);
  banner.appendChild(el('div', 'banner-title', g.name));
  banner.appendChild(el('div', 'banner-tag', g.tagline || ''));
  view.appendChild(banner);

  if (g.type === 'gdps') await renderGdpsPage(view, g);
  else if (g.type === 'mc') renderMcPage(view, g);
}

async function renderGdpsPage(view, g) {
  const st = await window.petus.gameStats(g.id);
  const installed = await window.petus.isInstalled();

  // Action row
  const actions = el('div', 'actions');
  const statusLine = el('div', 'muted status-line', installed ? 'Готов к запуску' : 'Игра не установлена');
  const progWrap = el('div', 'progress');
  progWrap.hidden = true;
  const progBar = el('div', 'progress-bar');
  progWrap.appendChild(progBar);

  const mainBtn = el('button', 'btn btn-primary big', installed ? 'Играть' : 'Установить игру');
  mainBtn.addEventListener('click', async () => {
    mainBtn.disabled = true;
    try {
      await window.petus.play();
      statusLine.textContent = 'Игра запущена!';
      // refresh page after (size/verify may have changed)
      setTimeout(() => selectGame(g.id), 800);
    } catch (e) {
      statusLine.textContent = 'Ошибка: ' + (e.message || e);
    } finally {
      mainBtn.disabled = false;
    }
  });

  const folderBtn = el('button', 'btn', 'Папка игры');
  folderBtn.addEventListener('click', () => window.petus.openFolder());

  const verifyBtn = el('button', 'btn', 'Проверить целостность');
  verifyBtn.addEventListener('click', async () => {
    const r = await window.petus.verify();
    if (r.noSnapshot) alert('Игра ещё не установлена — нечего проверять.');
    else if (r.ok) alert('Проверка пройдена: файлы игры не изменены.');
    else
      alert(
        'ВНИМАНИЕ: изменены файлы игры:\n' +
          r.changed.join('\n') +
          '\n\nПереустанови игру, чтобы восстановить.'
      );
  });

  actions.append(mainBtn, folderBtn, verifyBtn);

  const wireProgress = window.petus.onProgress(({ stage, progress }) => {
    const TXT = { check: 'Проверка…', download: 'Загрузка…', install: 'Установка…', ready: 'Готово', uptodate: 'Актуальная версия' };
    statusLine.textContent = TXT[stage] || stage;
    if (stage === 'download') {
      progWrap.hidden = false;
      progBar.style.width = Math.round((progress || 0) * 100) + '%';
    } else if (stage === 'ready' || stage === 'uptodate') {
      progWrap.hidden = true;
    }
  });
  // clean listener when navigating away (best-effort)
  view._cleanup = wireProgress;

  view.append(actions, statusLine, progWrap);

  // Stats card
  const stCard = el('div', 'card');
  stCard.appendChild(el('div', 'card-title', 'Статистика'));
  const grid = el('div', 'stat-grid');
  grid.appendChild(statCell('Наиграно', fmtDuration(st.playSeconds)));
  grid.appendChild(statCell('Размер', fmtSize(st.sizeBytes)));
  grid.appendChild(statCell('Последний запуск', fmtDate(st.lastPlayed)));
  stCard.appendChild(grid);
  view.appendChild(stCard);

  // Updates card
  view.appendChild(updatesCard(g));
}

function renderMcPage(view, g) {
  const actions = el('div', 'actions');
  const playBtn = el('button', 'btn btn-primary big', 'Играть');
  playBtn.addEventListener('click', () => openMcModal(g));
  actions.appendChild(playBtn);
  view.appendChild(actions);

  const info = el('div', 'card');
  info.appendChild(el('div', 'card-title', 'О сервере'));
  info.appendChild(el('div', 'card-text', `Заходи на Minecraft-сервер Петус.<br>IP: <b>${g.ip}</b>`));
  view.appendChild(info);

  view.appendChild(updatesCard(g));
}

function openMcModal(g) {
  const body = el('div');
  body.appendChild(el('p', 'muted', 'Скопируй IP и вставь его в Minecraft → Сетевая игра → Добавить сервер.'));
  const row = el('div', 'ip-row');
  const ipBox = el('input', 'ip-box');
  ipBox.value = g.ip;
  ipBox.readOnly = true;
  const copyBtn = el('button', 'btn btn-primary', 'Копировать');
  copyBtn.addEventListener('click', async () => {
    await window.petus.copyIp(g.ip);
    copyBtn.textContent = 'Скопировано!';
    setTimeout(() => (copyBtn.textContent = 'Копировать'), 1500);
  });
  row.append(ipBox, copyBtn);
  body.appendChild(row);
  openModal(`Подключение — ${g.name}`, body);
}

function updatesCard(g) {
  const card = el('div', 'card');
  card.appendChild(el('div', 'card-title', 'Обновления'));
  const list = el('div', 'updates');
  for (const u of g.changelog || []) {
    const row = el('button', 'update-row');
    row.innerHTML = `<span class="u-ver">v${u.version}</span><span class="u-peek">${u.notes.slice(0, 48)}${u.notes.length > 48 ? '…' : ''}</span>`;
    row.addEventListener('click', () => {
      const b = el('div');
      b.appendChild(el('div', 'chg-ver', `Версия ${u.version}`));
      b.appendChild(el('div', 'chg-notes', u.notes));
      openModal(`${g.name} — обновление`, b);
    });
    list.appendChild(row);
  }
  card.appendChild(list);
  return card;
}

function statCell(label, value) {
  const c = el('div', 'stat-cell');
  c.appendChild(el('div', 'sc-val', value));
  c.appendChild(el('div', 'sc-label', label));
  return c;
}

// ---- boot -----------------------------------------------------------------
(async () => {
  GAMES = await window.petus.gamesList();
  renderSidebar();
  authed = await window.petus.getAuth();
  renderAuthState();
})();
