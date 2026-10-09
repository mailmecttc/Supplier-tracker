import { isWork, partMetrics, prevCumulative } from '../calc';
import { niceDate, WEEKDAYS } from '../dates';
import { esc, num, signed } from '../format';
import { app, commitDrafts, entryDaily, entryValue, render, toast, vendor } from '../state';
import { partName } from '../ui';
import type { Actions } from './types';

function gapHtml(plan: number, daily: number | null): string {
  if (daily === null) return '<span class="gap muted">Not entered</span>';
  if (plan < 0.5) return `<span class="gap neutral">No plan today${daily > 0 ? ` · +${num(daily)} extra` : ''}</span>`;
  const g = daily - plan;
  const cls = daily / plan >= 0.95 ? 's-ontrack' : daily / plan >= 0.85 ? 's-watch' : 's-behind';
  return `<span class="gap ${cls}">Gap ${signed(g)}</span>`;
}

export function entryHtml(): string {
  const v = vendor();
  if (!v) return '';
  const parts = v.parts.filter((p) => p.active);
  if (!parts.length) {
    return `<div class="empty"><p>${esc(v.name)} has no parts yet.</p><button class="btn" data-act="go" data-tab="setup">Add parts in Setup</button></div>`;
  }
  const working = isWork(v, app.date, app.data.holidays);
  const holiday = app.data.holidays.includes(app.date);
  const days = v.dispatchDays.map((on, i) => (on ? WEEKDAYS[i] : '')).filter(Boolean).join(', ');
  const note = holiday
    ? `<div class="note">${niceDate(app.date)} is a holiday. No plan today.</div>`
    : !working ? `<div class="note">Not a dispatch day for ${esc(v.name)} (dispatches ${esc(days)}).</div>` : '';
  const cards = parts.map((p, i) => {
    const m = partMetrics(app.data, v, p.id, app.date);
    const daily = entryDaily(v, p.id);
    const cumHint = v.mode === 'cum'
      ? `<div class="cum-hint" id="cum-${p.id}">${daily === null ? `Last till-date: ${num(prevCumulative(app.data, v.id, p.id, app.date))}` : `= <b class="num">${num(daily)}</b> dispatched today`}</div>`
      : '';
    return `
    <div class="card part-card">
      <div class="pc-head">
        <div><div class="pc-name">${esc(partName(p))}</div><div class="pc-no">${esc(p.partNo)}</div></div>
        ${p.critical ? '<span class="badge">Critical</span>' : ''}
      </div>
      <div class="pc-stats">
        <div><span>Today plan</span><b class="num">${m.todayPlan < 0.5 ? '–' : num(m.todayPlan)}</b></div>
        <div><span>Week act/plan</span><b class="num">${num(m.wtdActual)} / ${num(m.weekPlan)}</b></div>
        <div><span>Month act/req</span><b class="num">${num(m.mtdActual)} / ${num(m.mtdRequirement)}</b></div>
      </div>
      <label class="qty-row">
        <span>${v.mode === 'cum' ? 'Till-date total' : 'Dispatched today'}</span>
        <input class="qty num" type="text" inputmode="numeric" enterkeyhint="${i === parts.length - 1 ? 'done' : 'next'}"
          autocomplete="off" data-inp="qty" data-part="${p.id}" value="${esc(entryValue(v, p.id))}" placeholder="–">
      </label>
      ${cumHint}
      <div class="gap-line" id="gap-${p.id}">${gapHtml(m.todayPlan, daily)}</div>
    </div>`;
  }).join('');
  const dirty = Object.keys(app.drafts).length;
  return `
    ${note}
    <div class="mode-line muted">${v.mode === 'cum' ? 'Cumulative mode: type the month till-date total.' : 'Type today’s dispatch. Blank = not entered, 0 = nothing dispatched.'}</div>
    ${cards}
    <div class="save-bar"><button class="btn primary big" data-act="save-entry">${dirty ? `Save ${dirty} change${dirty > 1 ? 's' : ''}` : 'Save'}</button></div>`;
}

export const entryActions: Actions = {
  input: {
    qty(el) {
      const v = vendor();
      if (!v) return;
      const partId = el.dataset.part!;
      app.drafts[partId] = (el as HTMLInputElement).value;
      // live update without a full re-render so the keyboard stays open
      const m = partMetrics(app.data, v, partId, app.date);
      const daily = entryDaily(v, partId);
      const g = document.getElementById(`gap-${partId}`);
      if (g) g.innerHTML = gapHtml(m.todayPlan, daily);
      const c = document.getElementById(`cum-${partId}`);
      if (c) c.innerHTML = daily === null ? '' : `= <b class="num">${num(daily)}</b> dispatched today`;
      const btn = document.querySelector<HTMLButtonElement>('[data-act="save-entry"]');
      const n = Object.keys(app.drafts).length;
      if (btn) btn.textContent = `Save ${n} change${n > 1 ? 's' : ''}`;
    },
  },
  click: {
    'save-entry'() {
      const n = commitDrafts();
      toast(n ? `Saved ${n} part${n > 1 ? 's' : ''}` : 'Nothing to save');
      render();
    },
  },
};
