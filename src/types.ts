export type InputMode = 'daily' | 'cum';

export interface Part {
  id: string;
  partNo: string;
  desc: string;
  critical: boolean;
  active: boolean;
}

export interface Vendor {
  id: string;
  name: string;
  contact: string;
  mode: InputMode;
  /** Mon..Sun, 1 = dispatch day */
  dispatchDays: number[];
  parts: Part[];
  /** true for the removable example supplier */
  sample?: boolean;
}

export type IssueStatus = 'open' | 'closed';

export interface Issue {
  id: string;
  vendorId: string;
  partId: string;
  cause: string;
  action: string;
  owner: string;
  due: string;
  logged: string;
  status: IssueStatus;
}

export interface Data {
  version: 1;
  vendors: Vendor[];
  /** vendorId|partId|periodStart */
  plan: Record<string, number>;
  planOrig: Record<string, number>;
  /** vendorId|partId|YYYY-MM-DD */
  dispatch: Record<string, number>;
  cumulative: Record<string, number>;
  /** vendorId|partId|YYYY-MM */
  openingBacklog: Record<string, number>;
  commitment: Record<string, number>;
  issues: Issue[];
  holidays: string[];
  lastBackup: string;
}

export const GAP_CAUSES = [
  'Material shortage (tier-2)',
  'Shared machine loaded with other part',
  'Machine breakdown',
  'Fixture / tooling',
  'Quality hold / rejection',
  'Manpower',
  'Plan change (FTCI/MFTBC)',
  'Logistics / dispatch',
  'Other',
];

export function emptyData(): Data {
  return {
    version: 1,
    vendors: [],
    plan: {},
    planOrig: {},
    dispatch: {},
    cumulative: {},
    openingBacklog: {},
    commitment: {},
    issues: [],
    holidays: [],
    lastBackup: '',
  };
}
