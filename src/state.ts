// In-memory app state, shared by the screens. The data document is persisted on every change.
import { key, prevCumulative, recomputeCumulative } from './calc';
import { monthKey, todayLocal } from './dates';
import { parseQty } from './format';
import { persist } from './store';
import { emptyData, type Data, type Vendor } from './types';

export type Tab = 'entry' | 'status' | 'plan' | 'issues' | 'tower' | 'setup' | 'review';

export const app = {
  data: emptyData() as Data,
  vendorId: '',
  date: todayLocal(),
  tab: 'entry' as Tab,
  /** Unsaved Entry inputs, by part id (raw text). */
  drafts: {} as Record<string, string>,
  /** In-page confirm dialog key, e.g. 'del-vendor' */
  confirm: '' as string,
  issueFilter: 'open' as 'open' | 'overdue' | 'closed' | 'all',
  issueForm: false,
  planPeriod: '',
  slide: 0,
  toast: '',
};

let renderFn: () => void = () => {};
export function onRender(fn: () => void) {
  renderFn = fn;
}
export function render() {
  renderFn();
}

let saveTimer: ReturnType<typeof setTimeout> | undefined;
export function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    persist(app.data).catch(() => toast('Could not save to this device. Export a backup now.'));
  }, 150);
}
/** Flush pending save immediately (used before the page is hidden). */
export function flush() {
  clearTimeout(saveTimer);
  return persist(app.data);
}

let toastTimer: ReturnType<typeof setTimeout> | undefined;
export function toast(msg: string) {
  app.toast = msg;
  render();
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    app.toast = '';
    render();
  }, 2600);
}

export function vendor(): Vendor | undefined {
  return app.data.vendors.find((v) => v.id === app.vendorId) ?? app.data.vendors[0];
}

/** Writes Entry drafts into the data. Returns the number of parts saved. */
export function commitDrafts(): number {
  const v = vendor();
  const ids = Object.keys(app.drafts);
  if (!v || !ids.length) return 0;
  const d = app.date;
  let n = 0;
  for (const partId of ids) {
    const val = parseQty(app.drafts[partId]);
    const k = key(v.id, partId, d);
    if (v.mode === 'cum') {
      if (val === null) delete app.data.cumulative[k];
      else app.data.cumulative[k] = val;
      recomputeCumulative(app.data, v.id, partId, monthKey(d));
    } else if (val === null) {
      delete app.data.dispatch[k];
    } else {
      app.data.dispatch[k] = val;
    }
    n++;
  }
  app.drafts = {};
  save();
  return n;
}

/** Current Entry value for a part: draft if typed, else stored. */
export function entryValue(v: Vendor, partId: string): string {
  if (partId in app.drafts) return app.drafts[partId];
  const k = key(v.id, partId, app.date);
  const x = v.mode === 'cum' ? app.data.cumulative[k] : app.data.dispatch[k];
  return x === undefined ? '' : String(x);
}

/** Daily quantity implied by the Entry value (handles cumulative mode). */
export function entryDaily(v: Vendor, partId: string): number | null {
  const val = parseQty(entryValue(v, partId));
  if (val === null) return null;
  return v.mode === 'cum' ? val - prevCumulative(app.data, v.id, partId, app.date) : val;
}
