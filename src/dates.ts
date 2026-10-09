// All dates are 'YYYY-MM-DD' strings handled in UTC, so the device time zone never shifts a day.

const DAY = 86_400_000;

export function toDate(s: string): Date {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

export function fmt(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function addDays(s: string, n: number): string {
  return fmt(new Date(toDate(s).getTime() + n * DAY));
}

/** 0 = Monday … 6 = Sunday */
export function weekday(s: string): number {
  return (toDate(s).getUTCDay() + 6) % 7;
}

export function monthKey(s: string): string {
  return s.slice(0, 7);
}

export function monthStart(ym: string): string {
  return `${ym}-01`;
}

export function monthEnd(ym: string): string {
  const [y, m] = ym.split('-').map(Number);
  return fmt(new Date(Date.UTC(y, m, 0)));
}

export function prevMonth(ym: string): string {
  const [y, m] = ym.split('-').map(Number);
  return fmt(new Date(Date.UTC(y, m - 2, 1))).slice(0, 7);
}

export function nextMonth(ym: string): string {
  const [y, m] = ym.split('-').map(Number);
  return fmt(new Date(Date.UTC(y, m, 1))).slice(0, 7);
}

/** Inclusive list of dates from a to b. */
export function range(a: string, b: string): string[] {
  const out: string[] = [];
  for (let d = a; d <= b; d = addDays(d, 1)) out.push(d);
  return out;
}

export function isoWeek(s: string): number {
  const d = toDate(s);
  const thursday = new Date(d.getTime() + (3 - weekday(s)) * DAY);
  const jan1 = Date.UTC(thursday.getUTCFullYear(), 0, 1);
  return Math.floor((thursday.getTime() - jan1) / DAY / 7) + 1;
}

export function todayLocal(): string {
  const n = new Date();
  return fmt(new Date(Date.UTC(n.getFullYear(), n.getMonth(), n.getDate())));
}

const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const WD = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
export const WEEKDAYS = WD;

export function niceDate(s: string, withDay = false): string {
  if (!s) return '';
  const d = toDate(s);
  const base = `${d.getUTCDate()} ${MON[d.getUTCMonth()]}`;
  return withDay ? `${WD[weekday(s)]} ${base}` : base;
}

export function niceMonth(ym: string): string {
  const [y, m] = ym.split('-').map(Number);
  return `${MON[m - 1]} ${y}`;
}
