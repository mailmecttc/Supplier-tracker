import { key, periodsOfMonth, setPlan, dailyPlan } from '../calc';
import { addDays, monthKey, monthStart, niceDate, range, todayLocal, WEEKDAYS } from '../dates';
import { dispatchLogCsv, monthlySummaryCsv, saveFile } from '../exports';
import { esc, slug, uid } from '../format';
import { app, commitDrafts, render, save, toast, vendor } from '../state';
import { validateBackup } from '../store';
import type { Data, Vendor } from '../types';
import type { Actions } from './types';

let pendingRestore: Data | null = null;

export function getTheme(): string {
  try {
    return localStorage.getItem('theme') || 'auto';
  } catch {
    return 'auto';
  }
}

export function applyTheme() {
  const t = getTheme();
  if (t === 'auto') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', t);
}

function newVendor(name: string): Vendor {
  let id = slug(name);
  while (app.data.vendors.some((v) => v.id === id)) id = `${slug(name)}-${uid().slice(0, 3)}`;
  return { id, name, contact: '', mode: 'daily', dispatchDays: [1, 1, 1, 1, 1, 1, 0], parts: [] };
}

function addSample() {
  const v: Vendor = {
    id: 'example', name: 'Example Supplier', contact: 'example data, safe to delete', mode: 'daily',
    dispatchDays: [1, 1, 1, 1, 1, 1, 0], sample: true,
    parts: [
      { id: 'ex1', partNo: 'EX-1001', desc: 'Example bracket', critical: true, active: true },
      { id: 'ex2', partNo: 'EX-2002', desc: 'Example gear', critical: true, active: true },
      { id: 'ex3', partNo: 'EX-3003', desc: 'Example cover', critical: false, active: true },
    ],
  };
  app.data.vendors = app.data.vendors.filter((x) => x.id !== 'example');
  app.data.vendors.push(v);
  const ym = monthKey(app.date);
  const weekly = [1800, 900, 1200];
  const perf = [0.97, 0.78, 0.9];
  periodsOfMonth(ym).forEach((p) => {
    const days = range(p.start, p.end).length;
    v.parts.forEach((pt, i) => setPlan(app.data, v.id, pt.id, p.start, Math.round((weekly[i] * days) / 7 / 10) * 10));
  });
  app.data.openingBacklog[key(v.id, 'ex2', ym)] = 400;
  for (const d of range(monthStart(ym), addDays(app.date, -1))) {
    v.parts.forEach((pt, i) => {
      const plan = dailyPlan(app.data, v, pt.id, d);
      if (plan > 0) {
        const wobble = 1 + 0.15 * Math.sin(Number(d.slice(8)) * (i + 2));
        app.data.dispatch[key(v.id, pt.id, d)] = Math.max(0, Math.round((plan * perf[i] * wobble) / 10) * 10);
      }
    });
  }
  app.data.issues.push({
    id: uid('i'), vendorId: v.id, partId: 'ex2', cause: 'Material shortage (tier-2)',
    action: 'Example: expedite forgings from tier-2', owner: 'Example owner', due: addDays(app.date, 2),
    logged: addDays(app.date, -3), status: 'open',
  });
  app.vendorId = v.id;
}

function removeVendorData(id: string) {
  app.data.vendors = app.data.vendors.filter((v) => v.id !== id);
  for (const m of ['plan', 'planOrig', 'dispatch', 'cumulative', 'openingBacklog', 'commitment'] as const) {
    for (const k of Object.keys(app.data[m])) if (k.startsWith(id + '|')) delete app.data[m][k];
  }
  app.data.issues = app.data.issues.filter((i) => i.vendorId !== id);
  if (app.vendorId === id) app.vendorId = app.data.vendors[0]?.id ?? '';
}

function confirmBox(id: string, text: string, okAct: string, okLabel: string, extra = '') {
  if (app.confirm !== id) return '';
  return `<div class="confirm"><p>${text}</p><div class="btn-row"><button class="btn danger" data-act="${okAct}" ${extra}>${okLabel}</button><button class="btn ghost" data-act="cancel-confirm">Cancel</button></div></div>`;
}

function onboardingHtml(): string {
  return `
    <section class="card">
      <h2>Welcome</h2>
      <p>Add the suppliers you follow. Everything you enter stays on this phone.</p>
      <label>Your vendors, one per line<textarea id="ob-vendors" rows="5" placeholder="Vendor name"></textarea></label>
      <div class="btn-row"><button class="btn primary" data-act="ob-add">Add vendors</button></div>
      <p class="muted small">Want to look around first? <button class="link" data-act="sample-add">Load an example supplier</button> (clearly marked, delete it any time).</p>
    </section>
    ${restoreHtml()}`;
}

function restoreHtml(): string {
  return `
    <section class="card">
      <h3>Backup</h3>
      <p class="muted small">Last backup: ${app.data.lastBackup ? niceDate(app.data.lastBackup) : 'never'}. Save one weekly to Drive or WhatsApp-to-self.</p>
      <div class="btn-row">
        <button class="btn primary" data-act="backup-share">Back up (share)</button>
        <button class="btn" data-act="backup-dl">Download backup</button>
        <label class="btn">Restore from file<input type="file" accept="application/json,.json" data-chg="restore-file" hidden></label>
      </div>
      ${pendingRestore ? confirmBox('restore', `Replace everything on this phone with the backup (${pendingRestore.vendors.length} vendors, last backup ${pendingRestore.lastBackup ? niceDate(pendingRestore.lastBackup) : 'unknown'})?`, 'restore-ok', 'Replace my data') : ''}
    </section>`;
}

export function setupHtml(): string {
  const v = vendor();
  if (!v) return onboardingHtml();
  const days = WEEKDAYS.map((d, i) => `<button class="day ${v.dispatchDays[i] ? 'on' : ''}" data-act="day" data-i="${i}">${d}</button>`).join('');
  const parts = v.parts.map((p) => `
    <div class="part-edit">
      <input type="text" placeholder="Part no." value="${esc(p.partNo)}" data-chg="part-field" data-part="${p.id}" data-field="partNo">
      <input type="text" placeholder="Description" value="${esc(p.desc)}" data-chg="part-field" data-part="${p.id}" data-field="desc">
      <label class="check"><input type="checkbox" ${p.critical ? 'checked' : ''} data-chg="part-field" data-part="${p.id}" data-field="critical"> Critical</label>
      <label class="check"><input type="checkbox" ${p.active ? 'checked' : ''} data-chg="part-field" data-part="${p.id}" data-field="active"> Active</label>
      <button class="btn small ghost" data-act="ask" data-c="del-part-${p.id}">Remove</button>
      ${confirmBox(`del-part-${p.id}`, 'Remove this part? Its plans and dispatch stay in backups but will no longer show.', 'del-part', 'Remove', `data-part="${p.id}"`)}
    </div>`).join('');
  const hols = [...app.data.holidays].sort().map((h) => `<span class="chip on">${niceDate(h, true)} ${h.slice(0, 4)}<button class="x" data-act="hol-del" data-d="${h}" aria-label="Remove">×</button></span>`).join('');
  const theme = getTheme();
  return `
    <h2>Setup</h2>
    <section class="card form">
      <h3>Vendor</h3>
      <label>Name<input type="text" value="${esc(v.name)}" data-chg="v-field" data-field="name"></label>
      <label>Contact<input type="text" value="${esc(v.contact)}" data-chg="v-field" data-field="contact" placeholder="Name / phone"></label>
      <label>Address<textarea rows="2" data-chg="v-field" data-field="address" placeholder="Plant / dispatch address">${esc(v.address)}</textarea></label>
      <label>Notes<textarea rows="5" data-chg="v-field" data-field="notes" placeholder="Commitments, capacity, open points">${esc(v.notes)}</textarea></label>
      <label>How this vendor reports
        <select data-chg="v-field" data-field="mode">
          <option value="daily" ${v.mode === 'daily' ? 'selected' : ''}>Daily quantity (also weekly / lot dispatch)</option>
          <option value="cum" ${v.mode === 'cum' ? 'selected' : ''}>Cumulative till-date total for the month</option>
        </select></label>
      <div class="lbl">Dispatch days</div>
      <div class="days">${days}</div>
      <p class="muted small">Weekly dispatchers: turn on only their dispatch day, so they never look behind mid-week.</p>
    </section>
    <section class="card">
      <h3>Parts</h3>
      ${parts || '<p class="muted">No parts yet.</p>'}
      <button class="btn" data-act="add-part">+ Add part</button>
    </section>
    <section class="card form">
      <h3>Vendors</h3>
      <div class="inline"><input id="new-vendor" type="text" placeholder="New vendor name"><button class="btn" data-act="add-vendor">Add</button></div>
      <button class="btn danger ghost" data-act="ask" data-c="del-vendor">Delete ${esc(v.name)}</button>
      ${confirmBox('del-vendor', `Delete ${esc(v.name)} with all its parts, plans, dispatch and issues? This cannot be undone (unless you have a backup).`, 'del-vendor', 'Delete vendor')}
      ${app.data.vendors.some((x) => x.sample)
        ? '<button class="btn ghost" data-act="sample-del">Remove example supplier</button>'
        : '<button class="btn ghost" data-act="sample-add">Add example supplier</button>'}
    </section>
    <section class="card form">
      <h3>Holidays (all vendors)</h3>
      <div class="chips wrap">${hols || '<span class="muted">None</span>'}</div>
      <label>Add holiday<input type="date" data-chg="hol-add"></label>
    </section>
    ${restoreHtml()}
    <section class="card">
      <h3>Exports</h3>
      <div class="btn-row">
        <button class="btn" data-act="csv-all-summary">Monthly summary CSV (all)</button>
        <button class="btn" data-act="csv-all-log">Dispatch log CSV (all)</button>
      </div>
    </section>
    <section class="card form">
      <h3>Theme</h3>
      <div class="chips">${['auto', 'light', 'dark'].map((t) => `<button class="chip ${theme === t ? 'on' : ''}" data-act="theme" data-t="${t}">${t[0].toUpperCase() + t.slice(1)}</button>`).join('')}</div>
    </section>
    <p class="muted small center">Data stays on this device (IndexedDB). No server, no tracking.</p>`;
}

async function backup(share: boolean) {
  commitDrafts();
  app.data.lastBackup = todayLocal();
  save();
  const r = await saveFile(`supplier-tracker-backup-${todayLocal()}.json`, JSON.stringify(app.data, null, 1), 'application/json', share);
  if (r === 'cancelled') return;
  toast(r === 'shared' ? 'Backup shared' : 'Backup downloaded');
}

export const setupActions: Actions = {
  click: {
    'ob-add'() {
      const names = ((document.getElementById('ob-vendors') as HTMLTextAreaElement).value || '')
        .split('\n').map((s) => s.trim()).filter(Boolean);
      if (!names.length) return toast('Type at least one vendor name');
      for (const n of names) app.data.vendors.push(newVendor(n));
      app.vendorId = app.data.vendors[0].id;
      save();
      toast(`Added ${names.length} vendor${names.length > 1 ? 's' : ''}. Now add parts.`);
    },
    'add-vendor'() {
      const el = document.getElementById('new-vendor') as HTMLInputElement;
      const name = el.value.trim();
      if (!name) return toast('Type a vendor name');
      const v = newVendor(name);
      app.data.vendors.push(v);
      app.vendorId = v.id;
      save();
      toast(`Added ${name}`);
    },
    ask(el) {
      app.confirm = el.dataset.c!;
      render();
    },
    'cancel-confirm'() {
      app.confirm = '';
      pendingRestore = null;
      render();
    },
    'del-vendor'() {
      const v = vendor()!;
      removeVendorData(v.id);
      app.confirm = '';
      save();
      toast(`Deleted ${v.name}`);
    },
    'del-part'(el) {
      const v = vendor()!;
      v.parts = v.parts.filter((p) => p.id !== el.dataset.part);
      app.confirm = '';
      save();
      render();
    },
    'add-part'() {
      vendor()!.parts.push({ id: uid('p'), partNo: '', desc: '', critical: true, active: true });
      save();
      render();
      const inputs = document.querySelectorAll<HTMLInputElement>('.part-edit input[data-field="partNo"]');
      inputs[inputs.length - 1]?.focus();
    },
    day(el) {
      const v = vendor()!;
      const i = Number(el.dataset.i);
      v.dispatchDays[i] = v.dispatchDays[i] ? 0 : 1;
      save();
      render();
    },
    'hol-del'(el) {
      app.data.holidays = app.data.holidays.filter((h) => h !== el.dataset.d);
      save();
      render();
    },
    'sample-add'() {
      addSample();
      save();
      toast('Example supplier added');
    },
    'sample-del'() {
      for (const v of app.data.vendors.filter((x) => x.sample)) removeVendorData(v.id);
      save();
      toast('Example supplier removed');
    },
    'backup-share'() {
      backup(true);
    },
    'backup-dl'() {
      backup(false);
    },
    'restore-ok'() {
      if (!pendingRestore) return;
      app.data = pendingRestore;
      pendingRestore = null;
      app.confirm = '';
      app.drafts = {};
      app.vendorId = app.data.vendors[0]?.id ?? '';
      save();
      toast('Backup restored');
    },
    theme(el) {
      try {
        localStorage.setItem('theme', el.dataset.t!);
      } catch {
        /* ignore */
      }
      applyTheme();
      render();
    },
    async 'csv-all-summary'() {
      await saveFile(`summary-all-${app.date}.csv`, monthlySummaryCsv(app.data, app.data.vendors, app.date), 'text/csv', true);
    },
    async 'csv-all-log'() {
      await saveFile(`dispatch-all-${app.date}.csv`, dispatchLogCsv(app.data, app.data.vendors, app.date), 'text/csv', true);
    },
  },
  change: {
    'v-field'(el) {
      const v = vendor()! as unknown as Record<string, unknown>;
      v[el.dataset.field!] = (el as HTMLInputElement).value.trim();
      save();
      render();
    },
    'part-field'(el) {
      const p = vendor()!.parts.find((x) => x.id === el.dataset.part);
      if (!p) return;
      const input = el as HTMLInputElement;
      const f = el.dataset.field as 'partNo' | 'desc' | 'critical' | 'active';
      if (f === 'critical' || f === 'active') p[f] = input.checked;
      else p[f] = input.value.trim();
      save();
    },
    'hol-add'(el) {
      const d = (el as HTMLInputElement).value;
      if (d && !app.data.holidays.includes(d)) app.data.holidays.push(d);
      save();
      render();
    },
    async 'restore-file'(el) {
      const f = (el as HTMLInputElement).files?.[0];
      if (!f) return;
      try {
        pendingRestore = validateBackup(await f.text());
        app.confirm = 'restore';
        render();
      } catch (e) {
        toast((e as Error).message);
      }
    },
  },
};
