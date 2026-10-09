import './style.css';
import { addDays, niceDate, toDate, todayLocal } from './dates';
import { esc } from './format';
import { app, commitDrafts, flush, onRender, render, type Tab, vendor } from './state';
import { load, requestPersistence } from './store';
import { entryActions, entryHtml } from './screens/entry';
import { issuesActions, issuesHtml } from './screens/issues';
import { planActions, planHtml } from './screens/plan';
import { reviewActions, reviewHtml } from './screens/review';
import { applyTheme, setupActions, setupHtml } from './screens/setup';
import { statusActions, statusHtml } from './screens/status';
import { towerActions, towerHtml } from './screens/tower';
import type { Actions, Handler } from './screens/types';

const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: 'entry', label: 'Entry', icon: '<path d="M4 20h16M6 16l9.5-9.5 3 3L9 19H6v-3z"/>' },
  { id: 'status', label: 'Status', icon: '<path d="M5 20V10M12 20V4M19 20v-7"/>' },
  { id: 'plan', label: 'Plan', icon: '<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M4 10h16M9 3v4M15 3v4"/>' },
  { id: 'issues', label: 'Issues', icon: '<path d="M12 8v5M12 16.5v.5"/><path d="M10.3 4.2 2.6 18a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z"/>' },
  { id: 'tower', label: 'All', icon: '<rect x="4" y="4" width="7" height="7" rx="1"/><rect x="13" y="4" width="7" height="7" rx="1"/><rect x="4" y="13" width="7" height="7" rx="1"/><rect x="13" y="13" width="7" height="7" rx="1"/>' },
  { id: 'setup', label: 'Setup', icon: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/>' },
];

const SCREENS: Record<Tab, () => string> = {
  entry: entryHtml, status: statusHtml, plan: planHtml, issues: issuesHtml, tower: towerHtml, setup: setupHtml, review: reviewHtml,
};

const shellActions: Actions = {
  click: {
    go(el) {
      commitDrafts();
      app.tab = el.dataset.tab as Tab;
      app.confirm = '';
      if (app.tab === 'review') app.slide = 0;
      render();
      window.scrollTo(0, 0);
    },
    'day-step'(el) {
      commitDrafts();
      app.date = addDays(app.date, Number(el.dataset.d));
      render();
    },
    today() {
      commitDrafts();
      app.date = todayLocal();
      render();
    },
    'backup-now'() {
      commitDrafts();
      app.tab = 'setup';
      render();
    },
  },
  change: {
    vendor(el) {
      commitDrafts();
      app.vendorId = (el as HTMLSelectElement).value;
      app.confirm = '';
      render();
    },
    date(el) {
      const v = (el as HTMLInputElement).value;
      if (!v) return;
      commitDrafts();
      app.date = v;
      render();
    },
  },
};

const all = [shellActions, entryActions, statusActions, planActions, issuesActions, towerActions, setupActions, reviewActions];
const handlers = (kind: keyof Actions) =>
  Object.assign({}, ...all.map((a) => a[kind] ?? {})) as Record<string, Handler>;
const CLICK = handlers('click');
const CHANGE = handlers('change');
const INPUT = handlers('input');

function backupDue(): boolean {
  if (!app.data.vendors.some((v) => !v.sample)) return false;
  if (!app.data.lastBackup) return true;
  return (toDate(todayLocal()).getTime() - toDate(app.data.lastBackup).getTime()) / 86_400_000 >= 7;
}

function header(): string {
  const v = vendor();
  const opts = app.data.vendors.map((x) => `<option value="${x.id}" ${x.id === v?.id ? 'selected' : ''}>${esc(x.name)}</option>`).join('');
  const isToday = app.date === todayLocal();
  return `
    <header class="top">
      <div class="top-row">
        ${app.data.vendors.length ? `<select class="vendor-sel" data-chg="vendor" aria-label="Vendor">${opts}</select>` : '<b class="brand">Supplier Tracker</b>'}
      </div>
      <div class="top-row date-row">
        <button class="icon-btn" data-act="day-step" data-d="-1" aria-label="Previous day">‹</button>
        <label class="date-wrap"><span>${niceDate(app.date, true)}</span><input type="date" value="${app.date}" data-chg="date" aria-label="Date"></label>
        <button class="icon-btn" data-act="day-step" data-d="1" aria-label="Next day">›</button>
        <button class="chip ${isToday ? 'on' : ''}" data-act="today">Today</button>
      </div>
    </header>`;
}

function tabbar(): string {
  return `<nav class="tabbar">${TABS.map((t) => `
    <button class="tab ${app.tab === t.id ? 'on' : ''}" data-act="go" data-tab="${t.id}" ${!app.data.vendors.length && t.id !== 'setup' ? 'disabled' : ''}>
      <svg viewBox="0 0 24 24" aria-hidden="true">${t.icon}</svg><span>${t.label}</span></button>`).join('')}</nav>`;
}

const root = document.getElementById('app')!;

function draw() {
  if (!app.data.vendors.length && app.tab !== 'setup') app.tab = 'setup';
  if (app.data.vendors.length && !app.data.vendors.some((v) => v.id === app.vendorId)) app.vendorId = app.data.vendors[0].id;

  // remember focus so a re-render does not drop the keyboard
  const a = document.activeElement as HTMLElement | null;
  const focusSel = a && root.contains(a) && (a.dataset.chg || a.dataset.inp)
    ? `[data-${a.dataset.chg ? 'chg' : 'inp'}="${a.dataset.chg || a.dataset.inp}"]${a.dataset.part ? `[data-part="${a.dataset.part}"]` : ''}${a.dataset.field ? `[data-field="${a.dataset.field}"]` : ''}`
    : '';

  const review = app.tab === 'review';
  root.className = review ? 'is-review' : '';
  root.innerHTML = review
    ? `<main class="screen">${reviewHtml()}</main>`
    : `${header()}
       ${backupDue() ? `<div class="banner">${app.data.lastBackup ? 'It has been a week since your last backup.' : 'Your data lives only on this phone. Save a backup file.'} <button class="link" data-act="backup-now">Back up now</button></div>` : ''}
       <main class="screen">${SCREENS[app.tab]()}</main>
       ${tabbar()}`;
  if (app.toast) root.insertAdjacentHTML('beforeend', `<div class="toast" role="status">${esc(app.toast)}</div>`);

  if (focusSel) {
    const el = root.querySelector<HTMLElement>(focusSel);
    if (el && el !== a) el.focus();
  }
}

onRender(draw);

root.addEventListener('click', (ev) => {
  const el = (ev.target as HTMLElement).closest<HTMLElement>('[data-act]');
  if (!el || (el as HTMLButtonElement).disabled) return;
  CLICK[el.dataset.act!]?.(el, ev);
});
root.addEventListener('change', (ev) => {
  const el = (ev.target as HTMLElement).closest<HTMLElement>('[data-chg]');
  if (el) CHANGE[el.dataset.chg!]?.(el, ev);
});
root.addEventListener('input', (ev) => {
  const el = (ev.target as HTMLElement).closest<HTMLElement>('[data-inp]');
  if (el) INPUT[el.dataset.inp!]?.(el, ev);
});
// Enter moves to the next quantity box (Entry and Plan)
root.addEventListener('keydown', (ev) => {
  const t = ev.target as HTMLElement;
  if (ev.key !== 'Enter' || !t.classList.contains('qty')) return;
  ev.preventDefault();
  const boxes = [...root.querySelectorAll<HTMLInputElement>('input.qty')];
  const next = boxes[boxes.indexOf(t as HTMLInputElement) + 1];
  if (next) next.focus();
  else t.blur();
});
// review: swipe between slides
let touchX = 0;
root.addEventListener('touchstart', (e) => (touchX = e.touches[0].clientX), { passive: true });
root.addEventListener('touchend', (e) => {
  if (app.tab !== 'review') return;
  const dx = e.changedTouches[0].clientX - touchX;
  if (Math.abs(dx) < 60) return;
  const btn = root.querySelector<HTMLButtonElement>(`[data-act="slide"][data-d="${dx < 0 ? 1 : -1}"]`);
  if (btn && !btn.disabled) btn.click();
});
document.addEventListener('keydown', (e) => {
  if (app.tab !== 'review') return;
  if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
    root.querySelector<HTMLButtonElement>(`[data-act="slide"][data-d="${e.key === 'ArrowRight' ? 1 : -1}"]`)?.click();
  }
});

// never lose typed entries when the app is backgrounded or closed
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') {
    commitDrafts();
    flush();
  }
});

async function start() {
  applyTheme();
  app.data = await load();
  app.vendorId = app.data.vendors[0]?.id ?? '';
  try {
    const saved = localStorage.getItem('vendorId');
    if (saved && app.data.vendors.some((v) => v.id === saved)) app.vendorId = saved;
  } catch {
    /* ignore */
  }
  render();
  requestPersistence();
  if ('serviceWorker' in navigator && import.meta.env.PROD) {
    navigator.serviceWorker.register('./sw.js').catch(() => {});
  }
}

// remember last vendor (per-device convenience only)
const origDraw = draw;
onRender(() => {
  origDraw();
  try {
    localStorage.setItem('vendorId', app.vendorId);
  } catch {
    /* ignore */
  }
});

start().catch((e) => {
  root.innerHTML = `<div class="empty"><p>Could not open storage on this device.</p><p class="muted small">${esc(String(e))}</p></div>`;
});
