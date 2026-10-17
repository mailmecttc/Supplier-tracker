"""Writes tools/guide-sample-data.json: made-up suppliers used only for the user guide's screenshots.
Usage: python3 tools/make-guide-sample-data.py
"""
import datetime as dt
import json
import os

D = dt.date
HOL = {D(2026, 10, 2)}
MON_SAT, SAT = [1, 1, 1, 1, 1, 1, 0], [0, 0, 0, 0, 0, 1, 0]


def period(d):
    mon = d - dt.timedelta(days=d.weekday())
    sun = mon + dt.timedelta(days=6)
    ms = d.replace(day=1)
    me = (ms + dt.timedelta(days=32)).replace(day=1) - dt.timedelta(days=1)
    return max(mon, ms), min(sun, me)


def days(a, b):
    while a <= b:
        yield a
        a += dt.timedelta(days=1)


def work(dd, d):
    return dd[d.weekday()] == 1 and d not in HOL


def daily(weekly, dd, d):
    s, e = period(d)
    n = sum(1 for x in days(s, e) if work(dd, x))
    return (weekly / n if work(dd, d) else 0) if n else (weekly if d == e else 0)


vendors = [
    dict(id='supplier-a', name='Supplier A', contact='Mr. Sample, +91 90000 00000', address='Plot 1, Sample Industrial Estate',
         notes='• Sample data for the user guide.', mode='daily', dispatchDays=MON_SAT,
         parts=[dict(id='p1', partNo='A-100', desc='Bracket', critical=True, active=True),
                dict(id='p2', partNo='A-200', desc='Gear', critical=False, active=True),
                dict(id='p3', partNo='A-300', desc='Cover', critical=False, active=True),
                dict(id='p4', partNo='A-400', desc='Shaft', critical=False, active=True)]),
    dict(id='supplier-b', name='Supplier B', contact='', address='', notes='• Reports till-date (month-to-date) totals.',
         mode='cum', dispatchDays=MON_SAT,
         parts=[dict(id='p1', partNo='B-10', desc='Housing', critical=True, active=True),
                dict(id='p2', partNo='B-20', desc='Pipe', critical=False, active=True)]),
    dict(id='supplier-c', name='Supplier C', contact='', address='', notes='• Dispatches once a week, on Saturday.',
         mode='daily', dispatchDays=SAT, holidays=['2026-10-22'] + [f'2026-11-{x:02d}' for x in range(7, 13)],
         parts=[dict(id='p1', partNo='C-1', desc='Spacer', critical=False, active=True)]),
]
weekly = {('supplier-a', 'p1'): 1200, ('supplier-a', 'p2'): 600, ('supplier-a', 'p3'): 900, ('supplier-a', 'p4'): 300,
          ('supplier-b', 'p1'): 1000, ('supplier-b', 'p2'): 500, ('supplier-c', 'p1'): 2000}
ratio = {('supplier-a', 'p1'): 0.7, ('supplier-a', 'p2'): 1.0, ('supplier-a', 'p3'): 0.9, ('supplier-a', 'p4'): 1.05,
         ('supplier-b', 'p1'): 0.96, ('supplier-b', 'p2'): 0.8}
dd_of = {v['id']: v['dispatchDays'] for v in vendors}
mode_of = {v['id']: v['mode'] for v in vendors}

plan, dispatch, cumulative = {}, {}, {}
starts = sorted({period(d)[0] for d in days(D(2026, 10, 1), D(2026, 10, 31))})
for (vid, pid), q in weekly.items():
    for s in starts:
        plan[f'{vid}|{pid}|{s}'] = q
    run = 0
    for d in days(D(2026, 10, 1), D(2026, 10, 9)):
        if (vid, pid) not in ratio:
            continue
        x = round(daily(q, dd_of[vid], d) * ratio[(vid, pid)])
        if x <= 0:
            continue
        dispatch[f'{vid}|{pid}|{d}'] = x
        run += x
        if mode_of[vid] == 'cum':
            cumulative[f'{vid}|{pid}|{d}'] = run
dispatch['supplier-c|p1|2026-10-03'] = 2000  # Saturday lot

# September, tracked day by day, for the week-on-week trend: Supplier A slips, B hovers, C is steady
sep_ratio = {'supplier-a': [0.98, 0.97, 0.96, 0.88, 0.84], 'supplier-b': [0.92, 0.95, 0.90, 0.93, 0.91], 'supplier-c': [1.0, 1.0, None, 1.0, 1.0]}
sep_starts = sorted({period(d)[0] for d in days(D(2026, 9, 1), D(2026, 9, 30))})
sep_run = {}
for (vid, pid), q in weekly.items():
    for i, s0 in enumerate(sep_starts):
        r = sep_ratio[vid][i]
        if r is None:
            continue
        plan[f'{vid}|{pid}|{s0}'] = q
        for d in days(s0, period(s0)[1]):
            x = round(daily(q, dd_of[vid], d) * r)
            if x <= 0:
                continue
            dispatch[f'{vid}|{pid}|{d}'] = x
            if mode_of[vid] == 'cum':
                sep_run[(vid, pid)] = sep_run.get((vid, pid), 0) + x
                cumulative[f'{vid}|{pid}|{d}'] = sep_run[(vid, pid)]

data = dict(
    version=1, vendors=vendors, plan=plan, planOrig=dict(plan), dispatch=dispatch, cumulative=cumulative,
    openingBacklog={'supplier-a|p1|2026-10': 300},
    commitment={},
    issues=[dict(id='i1', vendorId='supplier-a', partId='p1', cause='Material shortage (tier-2)',
                 action='Expedite castings from tier-2 supplier', owner='Buyer', due='2026-10-12', logged='2026-10-08', status='open'),
            dict(id='i2', vendorId='supplier-b', partId='p2', cause='Machine breakdown',
                 action='Spare spindle ordered; 2nd shift from Monday', owner='Vendor maintenance', due='2026-10-14', logged='2026-10-07', status='open')],
    holidays=['2026-10-02'], lastBackup='2026-10-05',
    closedPlan={}, closedActual={},
    notes=[dict(id='n1', vendorId='supplier-a', partId='p1', date='2026-10-06', text='Castings short at the tier-2 foundry; partial lot of 150 promised for Thursday.'),
           dict(id='n2', vendorId='supplier-a', partId='', date='2026-10-08', text='Weekly call: supplier will add a Saturday shift until the backlog is cleared.'),
           dict(id='n3', vendorId='supplier-a', partId='p1', date='2026-10-09', text='150 received, rest expected Monday.')],
)
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'guide-sample-data.json')
with open(out, 'w') as f:
    json.dump(data, f, indent=1)
print('wrote', out, len(dispatch), 'dispatch entries')
