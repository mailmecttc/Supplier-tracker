import { niceDate, todayLocal } from '../dates';
import { esc, uid } from '../format';
import { app, render, save, toast, vendor } from '../state';
import { GAP_CAUSES, type Data, type Issue } from '../types';
import { partName } from '../ui';
import type { Actions } from './types';

export function isOverdue(i: Issue, today = todayLocal()): boolean {
  return i.status === 'open' && !!i.due && i.due < today;
}

export function openIssues(data: Data, vendorId: string) {
  return data.issues.filter((i) => i.vendorId === vendorId && i.status === 'open');
}

export function issueCard(i: Issue, data: Data, withActions = true): string {
  const v = data.vendors.find((x) => x.id === i.vendorId);
  const p = v?.parts.find((x) => x.id === i.partId);
  const state = i.status === 'closed' ? 'closed' : isOverdue(i) ? 'overdue' : 'open';
  return `
    <div class="issue ${state}">
      <div class="is-head"><b>${esc(p ? partName(p) : 'Whole vendor')}</b><span class="tag t-${state}">${state === 'overdue' ? 'Overdue' : state === 'closed' ? 'Closed' : 'Open'}</span></div>
      <div class="is-cause">${esc(i.cause)}</div>
      ${i.action ? `<div class="is-action">${esc(i.action)}</div>` : ''}
      <div class="is-meta muted small">${i.owner ? `Owner ${esc(i.owner)} · ` : ''}${i.due ? `Due ${niceDate(i.due)} · ` : ''}Logged ${niceDate(i.logged)}</div>
      ${withActions ? `<div class="btn-row">
        <button class="btn small" data-act="issue-toggle" data-id="${i.id}">${i.status === 'open' ? 'Close' : 'Reopen'}</button>
        <button class="btn small ghost" data-act="issue-del" data-id="${i.id}">Delete</button></div>` : ''}
    </div>`;
}

export function issuesHtml(): string {
  const v = vendor();
  if (!v) return '';
  const all = app.data.issues.filter((i) => i.vendorId === v.id);
  const f = app.issueFilter;
  const list = all
    .filter((i) => f === 'all' || (f === 'closed' ? i.status === 'closed' : f === 'overdue' ? isOverdue(i) : i.status === 'open'))
    .sort((a, b) => (a.due || '9999').localeCompare(b.due || '9999'));
  const count = (fn: (i: Issue) => boolean) => all.filter(fn).length;
  const chip = (id: typeof f, label: string, n: number) =>
    `<button class="chip ${f === id ? 'on' : ''}" data-act="issue-filter" data-f="${id}">${label} <small>${n}</small></button>`;
  const parts = v.parts.filter((p) => p.active);
  const form = app.issueForm ? `
    <section class="card form">
      <h3>Log an issue</h3>
      <label>Part<select id="is-part"><option value="">Whole vendor</option>${parts.map((p) => `<option value="${p.id}">${esc(partName(p))}</option>`).join('')}</select></label>
      <label>Cause<select id="is-cause">${GAP_CAUSES.map((c) => `<option>${esc(c)}</option>`).join('')}</select></label>
      <label>Action<textarea id="is-action" rows="2" placeholder="What will be done"></textarea></label>
      <label>Owner<input id="is-owner" type="text" placeholder="Who"></label>
      <label>Due date<input id="is-due" type="date"></label>
      <div class="btn-row"><button class="btn primary" data-act="issue-add">Add issue</button><button class="btn ghost" data-act="issue-form">Cancel</button></div>
    </section>` : `<button class="btn primary big" data-act="issue-form">Log an issue</button>`;
  return `
    <h2>Issues · ${esc(v.name)}</h2>
    ${form}
    <div class="chips">${chip('open', 'Open', count((i) => i.status === 'open'))}${chip('overdue', 'Overdue', count((i) => isOverdue(i)))}${chip('closed', 'Closed', count((i) => i.status === 'closed'))}${chip('all', 'All', all.length)}</div>
    ${list.length ? list.map((i) => issueCard(i, app.data)).join('') : '<p class="muted">Nothing here.</p>'}`;
}

const val = (id: string) => (document.getElementById(id) as HTMLInputElement | null)?.value.trim() ?? '';

export const issuesActions: Actions = {
  click: {
    'issue-form'() {
      app.issueForm = !app.issueForm;
      render();
    },
    'issue-filter'(el) {
      app.issueFilter = el.dataset.f as typeof app.issueFilter;
      render();
    },
    'issue-add'() {
      const v = vendor()!;
      app.data.issues.push({
        id: uid('i'), vendorId: v.id, partId: val('is-part'), cause: val('is-cause'), action: val('is-action'),
        owner: val('is-owner'), due: val('is-due'), logged: app.date, status: 'open',
      });
      app.issueForm = false;
      app.issueFilter = 'open';
      save();
      toast('Issue logged');
    },
    'issue-toggle'(el) {
      const i = app.data.issues.find((x) => x.id === el.dataset.id);
      if (i) i.status = i.status === 'open' ? 'closed' : 'open';
      save();
      render();
    },
    'issue-del'(el) {
      app.data.issues = app.data.issues.filter((x) => x.id !== el.dataset.id);
      save();
      render();
    },
  },
};
