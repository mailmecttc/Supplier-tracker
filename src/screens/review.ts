// Weekly review: a presentation of the follow-up, one slide per vendor. Print → Save as PDF.
import { periodOf, STATUS_RANK, vendorSummary } from '../calc';
import { niceDate } from '../dates';
import { esc, num, pct, signed } from '../format';
import { app, render } from '../state';
import { kpi, meter, mtdChart, partName, pill } from '../ui';
import { isOverdue, issueCard, openIssues } from './issues';
import type { Actions } from './types';

function slides(): string[] {
  const d = app.date;
  const p = periodOf(d);
  const vendors = app.data.vendors.map((v) => ({ v, s: vendorSummary(app.data, v, d) }))
    .sort((a, b) => STATUS_RANK[b.s.status] - STATUS_RANK[a.s.status]);
  const onTrack = vendors.filter((x) => x.s.status === 'ontrack' || x.s.status === 'ahead').length;
  const partsBehind = vendors.reduce((a, x) => a + x.s.partsBehind, 0);
  const allOpen = app.data.issues.filter((i) => i.status === 'open');
  const overdue = allOpen.filter((i) => isOverdue(i, d));
  const head = (title: string) => `<header class="sl-head"><span>Supplier dispatch review</span><span>${p.label} · as of ${niceDate(d, true)}</span></header><h2>${title}</h2>`;

  const cover = `
    ${head('Summary')}
    <p class="sl-lead"><b>${onTrack} of ${vendors.length}</b> vendors on track for the month · <b>${partsBehind}</b> part${partsBehind === 1 ? '' : 's'} behind · <b>${allOpen.length}</b> open issue${allOpen.length === 1 ? '' : 's'}${overdue.length ? ` (<span class="neg">${overdue.length} overdue</span>)` : ''}</p>
    <table class="sl-table">
      <thead><tr><th>Vendor</th><th>Month</th><th>MTD adherence</th><th>Week act / plan</th><th>Short to date</th><th>Need/day</th><th>Parts behind</th><th>Open issues</th></tr></thead>
      <tbody>${vendors.map(({ v, s }) => `
        <tr><th>${esc(v.name)}</th><td>${pill(s.status)}</td>
        <td><div class="num">${pct(s.adherence)}</div>${meter(s.adherence, s.status)}</td>
        <td class="num">${num(s.wtdActual)} / ${num(s.weekPlan)}</td>
        <td class="num">${num(s.shortToDate)}</td><td class="num">${num(s.needPerDay)}</td>
        <td class="num">${s.partsBehind} / ${s.parts.length}</td><td class="num">${openIssues(app.data, v.id).length}</td></tr>`).join('')}
      </tbody>
    </table>`;

  const perVendor = vendors.map(({ v, s }) => {
    const rows = s.parts.map((x) => ({ part: v.parts.find((pp) => pp.id === x.partId)!, m: x.m }));
    const attention = rows.filter((r) => r.m.behind || r.m.weekStatus === 'watch' || r.m.monthStatus === 'watch')
      .sort((a, b) => a.m.mtdGap - b.m.mtdGap);
    const issues = openIssues(app.data, v.id);
    return `
      ${head(`${esc(v.name)} ${pill(s.status)}`)}
      <div class="kpis sl-kpis">
        ${kpi('MTD adherence', pct(s.adherence), `${num(s.mtdActual)} of ${num(s.mtdRequirement)}`)}
        ${kpi('Week to date', `${num(s.wtdActual)}`, `plan to date ${num(s.wtdPlan)} · week ${num(s.weekPlan)}`)}
        ${kpi('Short to date', num(s.shortToDate))}
        ${kpi('Need per day', num(s.needPerDay), 'to close the month')}
      </div>
      <div class="sl-cols">
        <div><h3>Month to date by part</h3>${mtdChart(rows)}</div>
        <div>
          <h3>Needs attention</h3>
          ${attention.length ? `<table class="sl-table small"><thead><tr><th>Part</th><th>Week</th><th>Month</th><th>Gap</th><th>Need/day</th></tr></thead><tbody>
            ${attention.map(({ part, m }) => `<tr><th>${esc(partName(part))}<small>${esc(part.desc ? part.partNo : '')}</small></th><td>${pill(m.weekStatus)}</td><td>${pill(m.monthStatus)}</td><td class="num neg">${signed(m.mtdGap)}</td><td class="num"><b>${num(m.monthReqPerDay)}</b></td></tr>`).join('')}
          </tbody></table>` : '<p class="muted">All parts on track.</p>'}
          <h3>Open issues</h3>
          ${issues.length ? issues.map((i) => issueCard(i, app.data, false)).join('') : '<p class="muted">None logged.</p>'}
        </div>
      </div>`;
  });

  const actions = `
    ${head('Actions and follow-ups')}
    ${allOpen.length ? `<table class="sl-table"><thead><tr><th>Vendor</th><th>Part</th><th>Cause</th><th>Action</th><th>Owner</th><th>Due</th></tr></thead><tbody>
      ${[...allOpen].sort((a, b) => (a.due || '9999').localeCompare(b.due || '9999')).map((i) => {
        const v = app.data.vendors.find((x) => x.id === i.vendorId);
        const part = v?.parts.find((x) => x.id === i.partId);
        return `<tr class="${isOverdue(i, d) ? 'overdue' : ''}"><th>${esc(v?.name ?? '')}</th><td>${esc(part ? partName(part) : 'All parts')}</td><td>${esc(i.cause)}</td><td>${esc(i.action)}</td><td>${esc(i.owner)}</td><td class="num">${i.due ? niceDate(i.due) : '–'}${isOverdue(i, d) ? ' <span class="tag t-overdue">Overdue</span>' : ''}</td></tr>`;
      }).join('')}</tbody></table>` : '<p class="muted">No open issues.</p>'}`;

  return [cover, ...perVendor, actions];
}

export function reviewHtml(): string {
  const all = slides();
  app.slide = Math.max(0, Math.min(app.slide, all.length - 1));
  return `
    <div class="review">
      <div class="rv-bar no-print">
        <button class="btn small ghost" data-act="go" data-tab="tower">✕ Close</button>
        <span class="muted small">${app.slide + 1} / ${all.length}</span>
        <div class="btn-row tight">
          <button class="btn small" data-act="slide" data-d="-1" ${app.slide === 0 ? 'disabled' : ''}>‹</button>
          <button class="btn small" data-act="slide" data-d="1" ${app.slide === all.length - 1 ? 'disabled' : ''}>›</button>
          <button class="btn small primary" data-act="print">PDF</button>
        </div>
      </div>
      ${all.map((s, i) => `<section class="slide ${i === app.slide ? 'cur' : ''}">${s}</section>`).join('')}
      <p class="muted small center no-print">Swipe or use ‹ › to move. PDF opens print: choose “Save as PDF”, landscape.</p>
    </div>`;
}

export const reviewActions: Actions = {
  click: {
    slide(el) {
      app.slide += Number(el.dataset.d);
      render();
      window.scrollTo(0, 0);
    },
    print() {
      window.print();
    },
  },
};
