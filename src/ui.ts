// Small HTML helpers shared by the screens.
import { STATUS_LABEL, type PartMetrics, type Status } from './calc';
import { esc, num, pct } from './format';
import type { Part } from './types';

export function pill(s: Status, prefix = ''): string {
  return `<span class="pill s-${s}">${prefix ? esc(prefix) + ' ' : ''}${STATUS_LABEL[s]}</span>`;
}

export function kpi(label: string, value: string, sub = '', cls = ''): string {
  return `<div class="kpi ${cls}"><div class="kpi-l">${esc(label)}</div><div class="kpi-v num">${value}</div>${sub ? `<div class="kpi-s">${sub}</div>` : ''}</div>`;
}

export function meter(r: number | null, s: Status): string {
  const w = r === null ? 0 : Math.max(0, Math.min(1, r)) * 100;
  return `<div class="meter"><div class="meter-f s-${s}" style="width:${w.toFixed(1)}%"></div></div>`;
}

export function partName(p: Part): string {
  return p.desc || p.partNo || 'Unnamed part';
}

/** Horizontal bar chart: required vs dispatched month to date, worst first. */
export function mtdChart(rows: { part: Part; m: PartMetrics }[]): string {
  if (!rows.length) return '<p class="muted">No parts yet.</p>';
  const sorted = [...rows].sort((a, b) => a.m.mtdGap - b.m.mtdGap);
  const max = Math.max(1, ...sorted.map((r) => Math.max(r.m.mtdRequirement, r.m.mtdActual)));
  const w = (n: number) => `${((Math.max(0, n) / max) * 100).toFixed(1)}%`;
  const bars = sorted.map((r) => `
    <div class="ch-row">
      <div class="ch-top"><span class="ch-l">${esc(partName(r.part))}</span><span class="ch-n num">${num(r.m.mtdActual)} / ${num(r.m.mtdRequirement)}</span></div>
      <div class="ch-track"><div class="ch-req" style="width:${w(r.m.mtdRequirement)}"></div><div class="ch-act s-${r.m.monthStatus}" style="width:${w(r.m.mtdActual)}"></div></div>
    </div>`).join('');
  return `
    <div class="chart-legend"><span><i class="lg-req"></i>Required to date</span><span><i class="lg-act"></i>Dispatched</span></div>
    <div class="chart" role="img" aria-label="Required vs dispatched by part, worst first">${bars}</div>`;
}

export function adherenceText(r: number | null): string {
  return pct(r);
}
