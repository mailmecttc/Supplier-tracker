import { periodOf, STATUS_RANK, vendorSummary } from '../calc';
import { niceDate } from '../dates';
import { dispatchLogCsv, monthlySummaryCsv, saveFile } from '../exports';
import { esc, num, pct } from '../format';
import { app, commitDrafts, render } from '../state';
import { meter, partName, pill } from '../ui';
import { isOverdue, openIssues } from './issues';
import type { Actions } from './types';

export function towerHtml(): string {
  const p = periodOf(app.date);
  const cards = app.data.vendors.map((v) => {
    const s = vendorSummary(app.data, v, app.date);
    const worst = [...s.parts]
      .filter((x) => x.m.mtdGap < -0.5)
      .sort((a, b) => a.m.mtdGap - b.m.mtdGap)
      .slice(0, 2)
      .map((x) => {
        const part = v.parts.find((pp) => pp.id === x.partId)!;
        return `<li><span>${esc(partName(part))}</span><b class="num neg">${num(x.m.mtdGap)}</b></li>`;
      }).join('');
    const issues = openIssues(app.data, v.id);
    const overdue = issues.filter((i) => isOverdue(i)).length;
    return { rank: STATUS_RANK[s.status], html: `
      <button class="card vcard" data-act="open-vendor" data-id="${v.id}">
        <div class="title-row"><h3>${esc(v.name)}${v.sample ? ' <span class="badge ghost">Example</span>' : ''}</h3>${pill(s.status)}</div>
        <div class="vc-main"><span class="vc-pct num">${pct(s.adherence)}</span><span class="muted small">MTD adherence · ${num(s.mtdActual)} of ${num(s.mtdRequirement)}</span></div>
        ${meter(s.adherence, s.status)}
        <div class="vc-stats">
          <div><span>Week act/plan</span><b class="num">${num(s.wtdActual)} / ${num(s.weekPlan)}</b></div>
          <div><span>Parts behind</span><b class="num ${s.partsBehind ? 'neg' : ''}">${s.partsBehind} / ${s.parts.length}</b></div>
          <div><span>Open issues</span><b class="num">${issues.length}${overdue ? ` <small class="neg">(${overdue} overdue)</small>` : ''}</b></div>
        </div>
        ${worst ? `<ul class="worst">${worst}</ul>` : ''}
      </button>` };
  }).sort((a, b) => b.rank - a.rank).map((x) => x.html).join('');
  return `
    <div class="title-row"><h2>All vendors</h2><button class="btn primary small" data-act="go" data-tab="review">Weekly review ▸</button></div>
    <p class="muted small">As of ${niceDate(app.date, true)} · ${p.label}. Tap a vendor for detail.</p>
    ${cards || '<p class="muted">No vendors yet.</p>'}
    <div class="btn-row">
      <button class="btn" data-act="csv-all-summary">All vendors summary CSV</button>
      <button class="btn" data-act="csv-all-log">All vendors dispatch log CSV</button>
    </div>`;
}

export const towerActions: Actions = {
  click: {
    'open-vendor'(el) {
      commitDrafts();
      app.vendorId = el.dataset.id!;
      app.tab = 'status';
      render();
    },
    async 'csv-all-summary'() {
      await saveFile(`summary-all-${app.date}.csv`, monthlySummaryCsv(app.data, app.data.vendors, app.date), 'text/csv', true);
    },
    async 'csv-all-log'() {
      await saveFile(`dispatch-all-${app.date}.csv`, dispatchLogCsv(app.data, app.data.vendors, app.date), 'text/csv', true);
    },
  },
};
