import { periodOf, vendorSummary } from '../calc';
import { niceDate } from '../dates';
import { dispatchLogCsv, monthlySummaryCsv, saveFile } from '../exports';
import { esc, num, pct, signed } from '../format';
import { app, toast, vendor } from '../state';
import { kpi, mtdChart, partName, pill } from '../ui';
import type { Actions } from './types';

export function statusHtml(): string {
  const v = vendor();
  if (!v) return '';
  const s = vendorSummary(app.data, v, app.date);
  const p = periodOf(app.date);
  const rows = s.parts.map((x) => ({ part: v.parts.find((pp) => pp.id === x.partId)!, m: x.m }));
  const table = rows
    .sort((a, b) => a.m.mtdGap - b.m.mtdGap)
    .map(({ part, m }) => `
      <tr>
        <th>${esc(partName(part))}<small>${esc(part.partNo)}</small></th>
        <td>${pill(m.weekStatus)}</td><td>${pill(m.monthStatus)}</td>
        <td class="num">${m.todayActual === undefined ? '–' : num(m.todayActual)} / ${num(m.todayPlan)}</td>
        <td class="num">${num(m.wtdActual)} / ${num(m.wtdPlan)}</td>
        <td class="num">${num(m.weekReqPerDay)}</td>
        <td class="num">${num(m.openingBacklog)}</td>
        <td class="num">${num(m.mtdActual)} / ${num(m.mtdRequirement)}</td>
        <td class="num ${m.mtdGap < -0.5 ? 'neg' : ''}">${signed(m.mtdGap)}</td>
        <td class="num">${num(m.monthBalance)}</td>
        <td class="num"><b>${num(m.monthReqPerDay)}</b></td>
      </tr>`).join('');
  return `
    <div class="title-row"><h2>${esc(v.name)}</h2>${pill(s.status)}</div>
    <p class="muted small">As of ${niceDate(app.date, true)} · ${p.label} (${niceDate(p.start)}–${niceDate(p.end)})</p>
    <div class="kpis">
      ${kpi('Month adherence', pct(s.adherence), `${num(s.mtdActual)} of ${num(s.mtdRequirement)}`, `s-txt-${s.status}`)}
      ${kpi('Short to date', num(s.shortToDate), 'sum of part gaps')}
      ${kpi('Parts behind', String(s.partsBehind), `of ${s.parts.length}`)}
      ${kpi('Today', s.todayEntered ? num(s.todayActual) : '–', `plan ${num(s.todayPlan)}`)}
      ${kpi('Week to date', num(s.wtdActual), `plan ${num(s.wtdPlan)}`)}
      ${kpi('Need per day', num(s.needPerDay), 'to close the month')}
    </div>
    <section class="card"><h3>Month to date by part</h3>${mtdChart(rows)}</section>
    <section class="card">
      <h3>Detail</h3>
      <div class="table-wrap"><table class="detail">
        <thead><tr><th>Part</th><th>Week</th><th>Month</th><th>Today act/plan</th><th>WTD act/plan</th><th>Week need/day</th>
        <th>Opening backlog</th><th>MTD act/req</th><th>MTD gap</th><th>Month balance</th><th>Need/day</th></tr></thead>
        <tbody>${table}</tbody>
      </table></div>
    </section>
    <div class="btn-row">
      <button class="btn" data-act="csv-summary">Monthly summary CSV</button>
      <button class="btn" data-act="csv-log">Dispatch log CSV</button>
    </div>`;
}

export const statusActions: Actions = {
  click: {
    async 'csv-summary'() {
      const v = vendor()!;
      await saveFile(`summary-${v.id}-${app.date}.csv`, monthlySummaryCsv(app.data, [v], app.date), 'text/csv', true);
    },
    async 'csv-log'() {
      const v = vendor()!;
      const r = await saveFile(`dispatch-${v.id}-${app.date}.csv`, dispatchLogCsv(app.data, [v], app.date), 'text/csv', true);
      if (r === 'downloaded') toast('CSV downloaded');
    },
  },
};
