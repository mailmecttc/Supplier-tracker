// CSV / JSON exports (spec section 7) and saving files on Android.
import { dailyPlan, STATUS_LABEL, vendorSummary } from './calc';
import { monthKey, monthStart, range } from './dates';
import type { Data, Vendor } from './types';

function cell(v: unknown): string {
  const s = typeof v === 'number' ? String(Math.round(v * 100) / 100) : String(v ?? '');
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export function toCsv(rows: unknown[][]): string {
  // UTF-8 BOM so Excel opens it cleanly
  return '﻿' + rows.map((r) => r.map(cell).join(',')).join('\r\n') + '\r\n';
}

/** Date, Vendor, Part No, Description, Daily plan, Dispatched, Gap — month to date. */
export function dispatchLogCsv(data: Data, vendors: Vendor[], asOf: string): string {
  const rows: unknown[][] = [['Date', 'Vendor', 'Part No', 'Description', 'Daily plan', 'Dispatched', 'Gap']];
  for (const d of range(monthStart(monthKey(asOf)), asOf)) {
    for (const v of vendors) {
      for (const p of v.parts.filter((x) => x.active)) {
        const plan = dailyPlan(data, v, p.id, d);
        const act = data.dispatch[`${v.id}|${p.id}|${d}`];
        if (plan === 0 && act === undefined) continue;
        rows.push([d, v.name, p.partNo, p.desc, plan, act ?? '', act === undefined ? '' : act - plan]);
      }
    }
  }
  return toCsv(rows);
}

export function monthlySummaryCsv(data: Data, vendors: Vendor[], asOf: string): string {
  const rows: unknown[][] = [
    ['As of', asOf],
    [],
    ['Vendor', 'Part No', 'Description', 'Opening backlog', 'Month plan', 'Required to date', 'Dispatched MTD',
      'Gap', 'Month balance', 'Need/day', 'Week status', 'Month status'],
  ];
  for (const v of vendors) {
    const s = vendorSummary(data, v, asOf);
    for (const { partId, m } of s.parts) {
      const p = v.parts.find((x) => x.id === partId)!;
      rows.push([v.name, p.partNo, p.desc, m.openingBacklog, m.monthPlan, m.mtdRequirement, m.mtdActual,
        m.mtdGap, m.monthBalance, m.monthReqPerDay, STATUS_LABEL[m.weekStatus], STATUS_LABEL[m.monthStatus]]);
    }
  }
  return toCsv(rows);
}

/**
 * Save a file. On Android Chrome the share sheet is the most reliable way to put a file
 * into Drive / WhatsApp / Files, so we offer it first and fall back to a normal download.
 */
export async function saveFile(name: string, text: string, mime: string, preferShare = false): Promise<'shared' | 'downloaded' | 'cancelled'> {
  const blob = new Blob([text], { type: mime });
  if (preferShare) {
    try {
      const file = new File([blob], name, { type: mime });
      if (navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: name });
        return 'shared';
      }
    } catch (e) {
      if ((e as Error).name === 'AbortError') return 'cancelled';
    }
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
  return 'downloaded';
}
