import {
  autoBacklog, key, monthPlan, openingBacklog, periodOf, periodPlan, periodsOfMonth, replan, setPlan, workDays,
} from '../calc';
import { addDays, monthKey, niceDate, niceMonth } from '../dates';
import { esc, num, parseQty, signed } from '../format';
import { app, render, save, toast, vendor } from '../state';
import { partName } from '../ui';
import type { Actions } from './types';

function selectedPeriod() {
  const ym = monthKey(app.date);
  const ps = periodsOfMonth(ym);
  return ps.find((p) => p.start === app.planPeriod) ?? periodOf(app.date);
}

export function planHtml(): string {
  const v = vendor();
  if (!v) return '';
  const ym = monthKey(app.date);
  const periods = periodsOfMonth(ym);
  const sel = selectedPeriod();
  const parts = v.parts.filter((p) => p.active);
  if (!parts.length) {
    return `<div class="empty"><p>Add parts for ${esc(v.name)} first.</p><button class="btn" data-act="go" data-tab="setup">Open Setup</button></div>`;
  }
  const n = workDays(v, sel.start, sel.end, app.data.holidays);
  const chips = periods.map((p) => `
    <button class="chip ${p.start === sel.start ? 'on' : ''}" data-act="period" data-start="${p.start}">
      ${p.label}<small>${niceDate(p.start)}–${niceDate(p.end).split(' ')[0]}</small></button>`).join('');

  const planRows = parts.map((p) => {
    const cur = app.data.plan[key(v.id, p.id, sel.start)];
    const rp = replan(app.data, v.id, p.id, sel.start);
    const per = cur && n ? `${num(cur / n)}/day over ${n} dispatch day${n > 1 ? 's' : ''}` : cur ? 'all on the last day (no dispatch day in this week)' : '';
    return `
      <div class="plan-row">
        <div class="pr-name">${esc(partName(p))}<small>${esc(p.partNo)}</small></div>
        <input class="qty num" type="text" inputmode="numeric" data-chg="plan" data-part="${p.id}" value="${cur ?? ''}" placeholder="0">
        <div class="pr-sub">${rp ? `<span class="replan">Original ${num(rp.original)}, re-planned ${signed(rp.diff)}</span>` : ''}<span class="muted">${per}</span></div>
      </div>`;
  }).join('');

  const backlogRows = parts.map((p) => {
    const manual = app.data.openingBacklog[key(v.id, p.id, ym)];
    return `
      <div class="plan-row">
        <div class="pr-name">${esc(partName(p))}<small>${esc(p.partNo)}</small></div>
        <input class="qty num" type="text" inputmode="numeric" data-chg="backlog" data-part="${p.id}"
          value="${manual ?? ''}" placeholder="auto ${num(autoBacklog(app.data, v, p.id, ym))}">
        <div class="pr-sub muted">${manual === undefined ? 'Carried forward automatically' : 'Entered by hand. Clear to use the auto value.'}</div>
      </div>`;
  }).join('');

  const matrix = `
    <div class="table-wrap"><table class="matrix">
      <thead><tr><th>Part</th>${periods.map((p) => `<th>${p.label}</th>`).join('')}<th>Month</th><th>Backlog</th><th>Total req</th></tr></thead>
      <tbody>${parts.map((p) => {
        const mp = monthPlan(app.data, v.id, p.id, ym);
        const ob = openingBacklog(app.data, v, p.id, ym);
        return `<tr><th>${esc(partName(p))}</th>${periods.map((pp) => {
          const rp = replan(app.data, v.id, p.id, pp.start);
          return `<td class="num ${rp ? 'replanned' : ''}">${num(periodPlan(app.data, v.id, p.id, pp.start)) }</td>`;
        }).join('')}<td class="num"><b>${num(mp)}</b></td><td class="num">${num(ob)}</td><td class="num"><b>${num(mp + ob)}</b></td></tr>`;
      }).join('')}</tbody>
    </table></div>
    <p class="muted small">Underlined weeks were re-planned.</p>`;

  return `
    <h2>Plan · ${niceMonth(ym)}</h2>
    <div class="chips">${chips}</div>
    <section class="card">
      <div class="title-row"><h3>${sel.label} weekly plan</h3><button class="btn small" data-act="copy-prev">Copy from previous week</button></div>
      ${planRows}
    </section>
    <section class="card"><h3>Opening backlog · ${niceMonth(ym)}</h3>${backlogRows}</section>
    <section class="card"><h3>Month plan</h3>${matrix}</section>`;
}

export const planActions: Actions = {
  click: {
    period(el) {
      app.planPeriod = el.dataset.start!;
      render();
    },
    'copy-prev'() {
      const v = vendor()!;
      const sel = selectedPeriod();
      const prev = periodOf(addDays(sel.start, -1));
      let n = 0;
      for (const p of v.parts.filter((x) => x.active)) {
        const x = app.data.plan[key(v.id, p.id, prev.start)];
        if (x !== undefined) {
          setPlan(app.data, v.id, p.id, sel.start, x);
          n++;
        }
      }
      save();
      toast(n ? `Copied ${n} plan${n > 1 ? 's' : ''} from ${prev.label}` : `${prev.label} has no plan to copy`);
    },
  },
  change: {
    plan(el) {
      const v = vendor()!;
      setPlan(app.data, v.id, el.dataset.part!, selectedPeriod().start, parseQty((el as HTMLInputElement).value));
      save();
      render();
    },
    backlog(el) {
      const v = vendor()!;
      const k = key(v.id, el.dataset.part!, monthKey(app.date));
      const val = parseQty((el as HTMLInputElement).value);
      if (val === null) delete app.data.openingBacklog[k];
      else app.data.openingBacklog[k] = val;
      save();
      render();
    },
  },
};
