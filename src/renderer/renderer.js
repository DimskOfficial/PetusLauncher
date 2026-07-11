'use strict';

const $ = (id) => document.getElementById(id);

const loginView = $('loginView');
const playView = $('playView');
const userChip = $('userChip');
const userName = $('userName');
const statusLine = $('statusLine');
const progressWrap = $('progressWrap');
const progressBar = $('progressBar');
const playBtn = $('playBtn');
const footVersion = $('footVersion');

const STAGE_TEXT = {
  check: 'Проверка обновлений…',
  download: 'Загрузка обновления…',
  install: 'Установка…',
  uptodate: 'Уже последняя версия',
  ready: 'Готов к запуску',
  error: 'Ошибка',
};

function showLogged(auth) {
  loginView.hidden = true;
  playView.hidden = false;
  userChip.hidden = false;
  userName.textContent = auth.name || 'Player';
}

function showLoggedOut() {
  loginView.hidden = false;
  playView.hidden = true;
  userChip.hidden = true;
}

async function refresh() {
  const auth = await window.petus.getAuth();
  if (auth && auth.token) showLogged(auth);
  else showLoggedOut();
}

$('loginBtn').addEventListener('click', async () => {
  $('loginBtn').disabled = true;
  try {
    const auth = await window.petus.login();
    showLogged(auth);
  } catch (e) {
    if (e && e.message !== 'cancelled') alert('Не удалось войти: ' + (e.message || e));
  } finally {
    $('loginBtn').disabled = false;
  }
});

$('logoutBtn').addEventListener('click', async (e) => {
  e.preventDefault();
  await window.petus.logout();
  showLoggedOut();
});

playBtn.addEventListener('click', async () => {
  playBtn.disabled = true;
  try {
    await window.petus.play();
    statusLine.textContent = 'Игра запущена!';
  } catch (e) {
    statusLine.textContent = 'Ошибка: ' + (e.message || e);
  } finally {
    playBtn.disabled = false;
  }
});

window.petus.onProgress(({ stage, progress, version, offline }) => {
  statusLine.textContent = STAGE_TEXT[stage] || stage;
  if (stage === 'download') {
    progressWrap.hidden = false;
    progressBar.style.width = Math.round((progress || 0) * 100) + '%';
  } else if (stage === 'ready' || stage === 'uptodate') {
    progressWrap.hidden = true;
    if (version) footVersion.textContent = 'Игра v' + version + (offline ? ' (офлайн)' : '');
  }
});

refresh();
