import { describe, expect, it } from 'vitest';
import {
  dailyPlan, key, monthPlan, openingBacklog, partMetrics, periodsOfMonth, recomputeCumulative,
  replan, setPlan, status,
} from '../src/calc';
import { emptyData, type Data, type Vendor } from '../src/types';
import { range } from '../src/dates';

const MON_SAT = [1, 1, 1, 1, 1, 1, 0];

function setup(dispatchDays = MON_SAT, mode: Vendor['mode'] = 'daily') {
  const data: Data = emptyData();
  const v: Vendor = {
    id: 'v1', name: 'Vendor', contact: '', mode, dispatchDays,
    parts: [{ id: 'p1', partNo: 'X-1', desc: 'Part', critical: true, active: true }],
  };
  data.vendors.push(v);
  return { data, v };
}

describe('section 8 acceptance tests', () => {
  it('1. periods: October 2026 is W40..W44 cut at month boundaries', () => {
    const ps = periodsOfMonth('2026-10').map((p) => [p.label, p.start, p.end]);
    expect(ps).toEqual([
      ['W40', '2026-10-01', '2026-10-04'],
      ['W41', '2026-10-05', '2026-10-11'],
      ['W42', '2026-10-12', '2026-10-18'],
      ['W43', '2026-10-19', '2026-10-25'],
      ['W44', '2026-10-26', '2026-10-31'],
    ]);
  });

  it('2. daily plan spread: 1,800 over Mon-Sat = 300/day, Sunday 0', () => {
    const { data, v } = setup();
    setPlan(data, 'v1', 'p1', '2026-10-05', 1800);
    for (const d of range('2026-10-05', '2026-10-10')) expect(dailyPlan(data, v, 'p1', d)).toBe(300);
    expect(dailyPlan(data, v, 'p1', '2026-10-11')).toBe(0);
  });

  it('3. weekly dispatcher (Saturday only): plan lands on Saturday, not Behind mid-week', () => {
    const { data, v } = setup([0, 0, 0, 0, 0, 1, 0]);
    setPlan(data, 'v1', 'p1', '2026-10-05', 1800);
    for (const d of range('2026-10-05', '2026-10-09')) expect(dailyPlan(data, v, 'p1', d)).toBe(0);
    expect(dailyPlan(data, v, 'p1', '2026-10-10')).toBe(1800);
    for (const d of range('2026-10-05', '2026-10-09')) {
      const m = partMetrics(data, v, 'p1', d);
      expect(m.weekStatus).not.toBe('behind');
      expect(m.monthStatus).not.toBe('behind');
    }
    // and on Saturday with nothing dispatched it is Behind
    data.dispatch[key('v1', 'p1', '2026-10-10')] = 0;
    expect(partMetrics(data, v, 'p1', '2026-10-10').weekStatus).toBe('behind');
  });

  it('4. holiday: no plan on 2 Oct, period plan spread over remaining working days', () => {
    const { data, v } = setup();
    data.holidays = ['2026-10-02'];
    setPlan(data, 'v1', 'p1', '2026-10-01', 900); // W40: Thu 1, Fri 2 (holiday), Sat 3
    expect(dailyPlan(data, v, 'p1', '2026-10-02')).toBe(0);
    expect(dailyPlan(data, v, 'p1', '2026-10-01')).toBe(450);
    expect(dailyPlan(data, v, 'p1', '2026-10-03')).toBe(450);
  });

  it('5. cumulative mode: 5,450 then 5,610 -> 160; editing first to 5,500 -> 110', () => {
    const { data } = setup(MON_SAT, 'cum');
    data.cumulative[key('v1', 'p1', '2026-10-07')] = 5450;
    data.cumulative[key('v1', 'p1', '2026-10-08')] = 5610;
    recomputeCumulative(data, 'v1', 'p1', '2026-10');
    expect(data.dispatch[key('v1', 'p1', '2026-10-08')]).toBe(160);
    expect(data.dispatch[key('v1', 'p1', '2026-10-07')]).toBe(5450);
    data.cumulative[key('v1', 'p1', '2026-10-07')] = 5500;
    recomputeCumulative(data, 'v1', 'p1', '2026-10');
    expect(data.dispatch[key('v1', 'p1', '2026-10-08')]).toBe(110);
  });

  it('6. backlog: 1,150 + month plan 7,470 = 8,620; MTD requirement day 1 = 1,150 + day-1 plan', () => {
    const { data, v } = setup();
    data.openingBacklog[key('v1', 'p1', '2026-10')] = 1150;
    const plans = [870, 1800, 1800, 1800, 1200]; // = 7,470
    periodsOfMonth('2026-10').forEach((p, i) => setPlan(data, 'v1', 'p1', p.start, plans[i]));
    expect(monthPlan(data, 'v1', 'p1', '2026-10')).toBe(7470);
    const m = partMetrics(data, v, 'p1', '2026-10-01');
    expect(m.openingBacklog + m.monthPlan).toBe(8620);
    expect(m.monthBalance).toBe(8620);
    expect(m.mtdRequirement).toBeCloseTo(1150 + dailyPlan(data, v, 'p1', '2026-10-01'));
    expect(dailyPlan(data, v, 'p1', '2026-10-01')).toBe(290); // 870 over Thu, Fri, Sat
  });

  it('7. zero plan: entering 0 on a no-plan day is neutral, not red', () => {
    const { data, v } = setup();
    data.dispatch[key('v1', 'p1', '2026-10-07')] = 0;
    const m = partMetrics(data, v, 'p1', '2026-10-07');
    expect(m.todayPlan).toBe(0);
    expect(m.weekStatus).toBe('noplan');
    expect(m.monthStatus).toBe('noplan');
    expect(m.behind).toBe(false);
  });

  it('8. re-plan: first save 1,800 then 1,500 shows original 1,800, re-planned -300', () => {
    const { data } = setup();
    setPlan(data, 'v1', 'p1', '2026-10-05', 1800);
    expect(replan(data, 'v1', 'p1', '2026-10-05')).toBeNull();
    setPlan(data, 'v1', 'p1', '2026-10-05', 1500);
    expect(replan(data, 'v1', 'p1', '2026-10-05')).toEqual({ original: 1800, diff: -300 });
    expect(data.plan[key('v1', 'p1', '2026-10-05')]).toBe(1500);
  });

  it('9. auto carry: November opening = max(0, Oct opening + Oct plan - Oct actual)', () => {
    const { data, v } = setup();
    data.openingBacklog[key('v1', 'p1', '2026-10')] = 1150;
    setPlan(data, 'v1', 'p1', '2026-10-05', 1800);
    data.dispatch[key('v1', 'p1', '2026-10-06')] = 1000;
    expect(openingBacklog(data, v, 'p1', '2026-11')).toBe(1150 + 1800 - 1000);
    data.dispatch[key('v1', 'p1', '2026-10-07')] = 5000;
    expect(openingBacklog(data, v, 'p1', '2026-11')).toBe(0);
    // manual entry wins
    data.openingBacklog[key('v1', 'p1', '2026-11')] = 42;
    expect(openingBacklog(data, v, 'p1', '2026-11')).toBe(42);
  });
});

describe('status thresholds', () => {
  it('maps ratios to statuses', () => {
    expect(status(0, 0, true)).toBe('noplan');
    expect(status(0, 10, true)).toBe('ahead');
    expect(status(100, 0, false)).toBe('notentered');
    expect(status(100, 95, true)).toBe('ontrack');
    expect(status(100, 85, true)).toBe('watch');
    expect(status(100, 84.9, true)).toBe('behind');
  });

  it('need per day spreads the balance over working days left', () => {
    const { data, v } = setup();
    setPlan(data, 'v1', 'p1', '2026-10-05', 1800);
    data.dispatch[key('v1', 'p1', '2026-10-05')] = 0;
    const m = partMetrics(data, v, 'p1', '2026-10-05');
    expect(m.weekBalance).toBe(1800);
    expect(m.weekReqPerDay).toBe(360); // Tue..Sat = 5 days
  });
});
