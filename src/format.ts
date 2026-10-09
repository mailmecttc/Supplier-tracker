const inr = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

/** Indian grouping, rounded: 123456 -> 1,23,456 */
export function num(n: number | undefined | null): string {
  if (n === undefined || n === null || Number.isNaN(n)) return '–';
  const r = Math.round(n);
  return inr.format(r === 0 ? 0 : r);
}

/** Signed, for gaps: +120 / −300 */
export function signed(n: number): string {
  const r = Math.round(n);
  if (r === 0) return '0';
  return (r > 0 ? '+' : '−') + inr.format(Math.abs(r));
}

export function pct(r: number | null): string {
  return r === null ? '–' : `${Math.round(r * 100)}%`;
}

export function esc(s: unknown): string {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

/** Parses a typed quantity; accepts Indian/Western grouping. Empty -> null. */
export function parseQty(s: string): number | null {
  const t = s.replace(/[,\s]/g, '');
  if (t === '') return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

export function uid(prefix = ''): string {
  return prefix + Math.random().toString(36).slice(2, 8) + Date.now().toString(36).slice(-4);
}

export function slug(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || 'vendor';
}
