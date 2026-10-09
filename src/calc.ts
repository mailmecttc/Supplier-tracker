// Pure calculation module (spec section 4). No DOM, no storage.
import type { Data, Vendor } from './types';
import {
  addDays, isoWeek, monthEnd, monthKey, monthStart, prevMonth, range, weekday,
} from './dates';

export const key = (...parts: string[]) => parts.join('|');

// ---------- periods ----------

export interface Period {
  start: string;
  end: string;
  /** ISO week number of the period start, e.g. 41 */
  week: number;
  label: string;
}

/** ISO week (Mon–Sun) cut at month boundaries. */
export function periodOf(d: string): Period {
  const ym = monthKey(d);
  const mon = addDays(d, -weekday(d));
  const sun = addDays(mon, 6);
  const ms = monthStart(ym);
  const me = monthEnd(ym);
  const start = mon < ms ? ms : mon;
  const end = sun > me ? me : sun;
  const week = isoWeek(start);
  return { start, end, week, label: `W${week}` };
}

export function periodsOfMonth(ym: string): Period[] {
  const out: Period[] = [];
  let d = monthStart(ym);
  const me = monthEnd(ym);
  while (d <= me) {
    const p = periodOf(d);
    out.push(p);
    d = addDays(p.end, 1);
  }
  return out;
}

// ---------- working days ----------

export function isWork(v: Vendor, d: string, holidays: string[]): boolean {
  return !!v.dispatchDays[weekday(d)] && !holidays.includes(d);
}

export function workDays(v: Vendor, a: string, b: string, holidays: string[]): number {
  if (a > b) return 0;
  return range(a, b).filter((d) => isWork(v, d, holidays)).length;
}

// ---------- plan ----------

export function periodPlan(data: Data, vendorId: string, partId: string, periodStart: string): number {
  return data.plan[key(vendorId, partId, periodStart)] ?? 0;
}

export function dailyPlan(data: Data, v: Vendor, partId: string, d: string): number {
  const p = periodOf(d);
  const P = periodPlan(data, v.id, partId, p.start);
  if (!P) return 0;
  const n = workDays(v, p.start, p.end, data.holidays);
  if (n === 0) return d === p.end ? P : 0;
  return isWork(v, d, data.holidays) ? P / n : 0;
}

export function sumPlan(data: Data, v: Vendor, partId: string, a: string, b: string): number {
  if (a > b) return 0;
  return range(a, b).reduce((s, d) => s + dailyPlan(data, v, partId, d), 0);
}

export function monthPlan(data: Data, vendorId: string, partId: string, ym: string): number {
  return periodsOfMonth(ym).reduce((s, p) => s + periodPlan(data, vendorId, partId, p.start), 0);
}

/** Saves a period plan; the first save is kept as the original plan. Mutates data. */
export function setPlan(data: Data, vendorId: string, partId: string, periodStart: string, value: number | null) {
  const k = key(vendorId, partId, periodStart);
  if (value === null || Number.isNaN(value)) {
    delete data.plan[k];
    return;
  }
  if (!(k in data.planOrig)) data.planOrig[k] = value;
  data.plan[k] = value;
}

/** Original plan and the re-plan difference (current − original), or null if never re-planned. */
export function replan(data: Data, vendorId: string, partId: string, periodStart: string) {
  const k = key(vendorId, partId, periodStart);
  const orig = data.planOrig[k];
  const cur = data.plan[k];
  if (orig === undefined || cur === undefined || orig === cur) return null;
  return { original: orig, diff: cur - orig };
}

// ---------- dispatch ----------

export function actual(data: Data, vendorId: string, partId: string, d: string): number | undefined {
  return data.dispatch[key(vendorId, partId, d)];
}

export function sumActual(data: Data, vendorId: string, partId: string, a: string, b: string) {
  let total = 0;
  let entered = false;
  if (a <= b) {
    for (const d of range(a, b)) {
      const x = actual(data, vendorId, partId, d);
      if (x !== undefined) {
        total += x;
        entered = true;
      }
    }
  }
  return { total, entered };
}

/**
 * Cumulative mode: rebuild every daily dispatch value of one part for one month from
 * the till-date totals. Mutates data.dispatch.
 */
export function recomputeCumulative(data: Data, vendorId: string, partId: string, ym: string) {
  let last = 0;
  for (const d of range(monthStart(ym), monthEnd(ym))) {
    const c = data.cumulative[key(vendorId, partId, d)];
    const dk = key(vendorId, partId, d);
    if (c === undefined) {
      delete data.dispatch[dk];
    } else {
      data.dispatch[dk] = c - last;
      last = c;
    }
  }
}

/** Last cumulative total entered before d in the same month (0 if none). */
export function prevCumulative(data: Data, vendorId: string, partId: string, d: string): number {
  const ms = monthStart(monthKey(d));
  for (let x = addDays(d, -1); x >= ms; x = addDays(x, -1)) {
    const c = data.cumulative[key(vendorId, partId, x)];
    if (c !== undefined) return c;
  }
  return 0;
}

// ---------- backlog ----------

export function openingBacklog(data: Data, v: Vendor, partId: string, ym: string, depth = 0): number {
  const manual = data.openingBacklog[key(v.id, partId, ym)];
  if (manual !== undefined) return manual;
  return autoBacklog(data, v, partId, ym, depth);
}

/** Carried-forward value, ignoring any manual entry for ym itself. */
export function autoBacklog(data: Data, v: Vendor, partId: string, ym: string, depth = 0): number {
  if (depth >= 12) return 0;
  const pm = prevMonth(ym);
  const prevOpening = openingBacklog(data, v, partId, pm, depth + 1);
  const prevPlan = monthPlan(data, v.id, partId, pm);
  const prevActual = sumActual(data, v.id, partId, monthStart(pm), monthEnd(pm)).total;
  return Math.max(0, prevOpening + prevPlan - prevActual);
}

// ---------- status ----------

export type Status = 'ahead' | 'noplan' | 'notentered' | 'ontrack' | 'watch' | 'behind';

export const STATUS_LABEL: Record<Status, string> = {
  ahead: 'Ahead',
  noplan: 'No plan',
  notentered: 'Not entered',
  ontrack: 'On track',
  watch: 'Watch',
  behind: 'Behind',
};

/** Severity for sorting (higher = worse). */
export const STATUS_RANK: Record<Status, number> = {
  behind: 5, watch: 4, notentered: 3, ontrack: 2, ahead: 1, noplan: 0,
};

export function status(plan: number, act: number, entered: boolean): Status {
  if (plan < 0.5) return act > 0 ? 'ahead' : 'noplan';
  if (!entered) return 'notentered';
  const r = act / plan;
  if (r >= 0.95) return 'ontrack';
  if (r >= 0.85) return 'watch';
  return 'behind';
}

// ---------- per-part metrics ----------

export interface PartMetrics {
  todayPlan: number;
  todayActual: number | undefined;
  weekPlan: number;
  wtdPlan: number;
  wtdActual: number;
  weekBalance: number;
  weekReqPerDay: number;
  openingBacklog: number;
  monthPlan: number;
  mtdPlan: number;
  mtdRequirement: number;
  mtdActual: number;
  mtdGap: number;
  monthBalance: number;
  monthReqPerDay: number;
  weekStatus: Status;
  monthStatus: Status;
  behind: boolean;
}

/** Need per day: balance spread over working days left after d (all of it if none are left). */
function perDay(balance: number, daysLeft: number) {
  if (balance <= 0) return 0;
  return daysLeft > 0 ? balance / daysLeft : balance;
}

export function partMetrics(data: Data, v: Vendor, partId: string, d: string): PartMetrics {
  const p = periodOf(d);
  const ym = monthKey(d);
  const ms = monthStart(ym);
  const me = monthEnd(ym);
  const hol = data.holidays;

  const todayPlan = dailyPlan(data, v, partId, d);
  const todayActual = actual(data, v.id, partId, d);
  const weekPlan = periodPlan(data, v.id, partId, p.start);
  const wtdPlan = sumPlan(data, v, partId, p.start, d);
  const wtd = sumActual(data, v.id, partId, p.start, d);
  const weekBalance = Math.max(0, weekPlan - wtd.total);
  const weekReqPerDay = perDay(weekBalance, workDays(v, addDays(d, 1), p.end, hol));

  const ob = openingBacklog(data, v, partId, ym);
  const mPlan = monthPlan(data, v.id, partId, ym);
  const mtdPlan = sumPlan(data, v, partId, ms, d);
  const mtdRequirement = ob + mtdPlan;
  const mtd = sumActual(data, v.id, partId, ms, d);
  const mtdGap = mtd.total - mtdRequirement;
  const monthBalance = Math.max(0, ob + mPlan - mtd.total);
  const monthReqPerDay = perDay(monthBalance, workDays(v, addDays(d, 1), me, hol));

  const weekStatus = status(wtdPlan, wtd.total, wtd.entered);
  const monthStatus = status(mtdRequirement, mtd.total, mtd.entered);

  return {
    todayPlan, todayActual, weekPlan, wtdPlan, wtdActual: wtd.total, weekBalance, weekReqPerDay,
    openingBacklog: ob, monthPlan: mPlan, mtdPlan, mtdRequirement, mtdActual: mtd.total, mtdGap,
    monthBalance, monthReqPerDay, weekStatus, monthStatus,
    behind: weekStatus === 'behind' || monthStatus === 'behind',
  };
}

// ---------- vendor roll-up ----------

export interface VendorSummary {
  parts: { partId: string; m: PartMetrics }[];
  todayPlan: number;
  todayActual: number;
  todayEntered: boolean;
  wtdPlan: number;
  wtdActual: number;
  weekPlan: number;
  mtdRequirement: number;
  mtdActual: number;
  shortToDate: number;
  needPerDay: number;
  partsBehind: number;
  adherence: number | null;
  status: Status;
  weekStatus: Status;
}

export function vendorSummary(data: Data, v: Vendor, d: string): VendorSummary {
  const parts = v.parts.filter((p) => p.active).map((p) => ({ partId: p.id, m: partMetrics(data, v, p.id, d) }));
  const s = (f: (m: PartMetrics) => number) => parts.reduce((a, x) => a + f(x.m), 0);
  const mtdRequirement = s((m) => m.mtdRequirement);
  const mtdActual = s((m) => m.mtdActual);
  const wtdPlan = s((m) => m.wtdPlan);
  const wtdActual = s((m) => m.wtdActual);
  const monthEntered = parts.some((x) => x.m.monthStatus !== 'notentered' && x.m.monthStatus !== 'noplan') ||
    parts.some((x) => x.m.mtdActual > 0);
  const weekEntered = parts.some((x) => x.m.weekStatus !== 'notentered' && x.m.weekStatus !== 'noplan') ||
    parts.some((x) => x.m.wtdActual > 0);
  return {
    parts,
    todayPlan: s((m) => m.todayPlan),
    todayActual: s((m) => m.todayActual ?? 0),
    todayEntered: parts.some((x) => x.m.todayActual !== undefined),
    wtdPlan,
    wtdActual,
    weekPlan: s((m) => m.weekPlan),
    mtdRequirement,
    mtdActual,
    shortToDate: s((m) => Math.max(0, -m.mtdGap)),
    needPerDay: s((m) => m.monthReqPerDay),
    partsBehind: parts.filter((x) => x.m.behind).length,
    adherence: mtdRequirement >= 0.5 ? mtdActual / mtdRequirement : null,
    status: status(mtdRequirement, mtdActual, monthEntered),
    weekStatus: status(wtdPlan, wtdActual, weekEntered),
  };
}
