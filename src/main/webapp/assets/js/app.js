const API = (() => {
  if (typeof window === 'undefined') return '/soundsphere/api';
  const loc = window.location;
  if (loc.protocol === 'file:') return 'http://localhost:8080/soundsphere/api';
  if (loc.port && loc.port !== '8080') return `${loc.protocol}//${loc.hostname}:8080/soundsphere/api`;
  return '/soundsphere/api';
})();

let csrf = '';

async function request(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (opts.body && !(opts.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }
  if (csrf && !['GET', 'HEAD', 'OPTIONS'].includes((opts.method || 'GET').toUpperCase())) {
    headers['X-CSRF-Token'] = csrf;
  }
  const res = await fetch(API + path, { credentials: 'include', ...opts, headers });
  const type = res.headers.get('content-type') || '';
  let data = null;
  if (type.includes('json')) {
    data = await res.json().catch(() => null);
  } else {
    const text = await res.text().catch(() => '');
    if (!res.ok) throw new Error(text || `Request failed (${res.status})`);
  }
  if (!res.ok) throw new Error(data?.error || `Request failed (${res.status})`);
  if (data?.csrf) csrf = data.csrf;
  return data;
}

function toast(message, error = false) {
  let n = document.querySelector('#toast');
  if (!n) {
    n = document.createElement('div');
    n.id = 'toast';
    n.className = 'toast';
    document.body.append(n);
  }
  n.textContent = message;
  n.classList.toggle('error', error);
  n.classList.remove('hidden');
  setTimeout(() => n.classList.add('hidden'), 3500);
}

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[c]));
}

function fmt(v) {
  return v ? new Date(v).toLocaleString() : '—';
}

async function me() {
  try {
    const d = await request('/auth/me');
    if (d?.csrf) csrf = d.csrf;
    return d || { authenticated: false };
  } catch {
    return { authenticated: false };
  }
}

function requireRole(user, role) {
  if (!user || !user.authenticated) {
    location.href = 'login.html';
    return false;
  }
  if (role && user.role !== role) {
    location.href = '403.html';
    return false;
  }
  return true;
}

function nav(role) {
  const items = role === 'ADMIN' ? [
    ['admin-dashboard.html', 'Overview'],
    ['admin-users.html', 'Users'],
    ['admin-moderation.html', 'Moderation'],
    ['admin-settings.html', 'Settings'],
    ['admin-activity.html', 'Activity']
  ] : [
    ['musician-dashboard.html', 'Overview'],
    ['projects.html', 'Projects'],
    ['music-files.html', 'Music files'],
    ['portfolio.html', 'Portfolio'],
    ['notifications.html', 'Notifications'],
    ['profile.html', 'Profile']
  ];
  return `<aside class="sidebar"><a class="brand" href="index.html">Sound<span>Sphere</span></a><nav class="nav">${items.map(([u, t]) => `<a href="${u}" class="${location.pathname.endsWith(u) ? 'active' : ''}">${t}</a>`).join('')}<a href="#" id="logout">↗ Sign out</a></nav><div class="side-bottom">Make music together.<br>SoundSphere • Java + MySQL</div></aside>`;
}

async function shell(title, role) {
  const user = await me();
  if (!requireRole(user, role)) return null;
  document.body.innerHTML = `<div class="layout">${nav(user.role)}<main class="main"><div class="topbar"><div><div class="eyebrow">${user.role === 'ADMIN' ? 'Administration' : 'Creator studio'}</div><h1 class="h1">${esc(title)}</h1><div class="muted">Welcome back, ${esc(user.name || 'artist')}</div></div><div class="profile-line"><div class="avatar">${esc((user.name || 'S')[0].toUpperCase())}</div><div><b>${esc(user.name || '')}</b><div class="muted">${esc(user.role)}</div></div></div></div><div id="app"></div></main></div><div id="toast" class="toast hidden"></div>`;
  document.querySelector('#logout').onclick = async e => {
    e.preventDefault();
    try {
      await request('/auth/logout', { method: 'POST', body: '{}' });
    } catch {}
    location.href = 'index.html';
  };
  return { user, app: document.querySelector('#app') };
}

function table(headers, rows) {
  return `<div class="table-wrap"><table><thead><tr>${headers.map(h => `<th>${h}</th>`).join('')}</tr></thead><tbody>${rows || `<tr><td colspan="${headers.length}" class="empty">No records yet.</td></tr>`}</tbody></table></div>`;
}

function field(label, name, type = 'text', required = false, extra = '') {
  return `<div class="field"><label for="${name}">${label}</label><input id="${name}" name="${name}" type="${type}" ${required ? 'required' : ''} ${extra}></div>`;
}

function area(label, name) {
  return `<div class="field full"><label for="${name}">${label}</label><textarea id="${name}" name="${name}"></textarea></div>`;
}

function dateInput(label, name) {
  return field(label, name, 'date');
}

function formDataObject(form) {
  return Object.fromEntries(new FormData(form).entries());
}
