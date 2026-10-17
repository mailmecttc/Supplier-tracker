// Persistence: one IndexedDB record holding the whole data document, plus backup/restore.
import Dexie, { type Table } from 'dexie';
import { emptyData, type Data } from './types';

interface Row {
  id: string;
  data: Data;
}

class TrackerDB extends Dexie {
  docs!: Table<Row, string>;
  constructor() {
    super('supplier-tracker');
    this.version(1).stores({ docs: 'id' });
  }
}

const db = new TrackerDB();
const MAIN = 'main';

export async function load(): Promise<Data> {
  const row = await db.docs.get(MAIN);
  return row ? normalise(row.data) : emptyData();
}

export async function persist(data: Data) {
  await db.docs.put({ id: MAIN, data: structuredClone(data) });
}

/** Ask the browser not to evict our storage. Best effort. */
export async function requestPersistence() {
  try {
    if (navigator.storage?.persist) await navigator.storage.persist();
  } catch {
    /* ignore */
  }
}

function normalise(d: Partial<Data>): Data {
  const base = emptyData();
  return { ...base, ...d, version: 1 } as Data;
}

const isObj = (x: unknown): x is Record<string, unknown> => !!x && typeof x === 'object' && !Array.isArray(x);
const numMap = (x: unknown) => isObj(x) && Object.values(x).every((n) => typeof n === 'number' && Number.isFinite(n));

/** Validates a backup file. Returns the data or throws with a readable message. */
export function validateBackup(text: string): Data {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new Error('This file is not valid JSON.');
  }
  if (!isObj(raw)) throw new Error('This file is not a tracker backup.');
  if (raw.version !== 1) throw new Error('Unknown backup version.');
  if (!Array.isArray(raw.vendors)) throw new Error('Backup has no vendor list.');
  for (const v of raw.vendors as unknown[]) {
    if (!isObj(v) || typeof v.id !== 'string' || typeof v.name !== 'string' || !Array.isArray(v.parts) ||
      !Array.isArray(v.dispatchDays) || v.dispatchDays.length !== 7 ||
      (v.address !== undefined && typeof v.address !== 'string') || (v.notes !== undefined && typeof v.notes !== 'string')) {
      throw new Error('A vendor in the backup is malformed.');
    }
  }
  for (const k of ['plan', 'planOrig', 'dispatch', 'cumulative', 'openingBacklog', 'commitment']) {
    if (raw[k] !== undefined && !numMap(raw[k])) throw new Error(`Backup section "${k}" is malformed.`);
  }
  if (raw.issues !== undefined && !Array.isArray(raw.issues)) throw new Error('Backup issue list is malformed.');
  if (raw.holidays !== undefined && !Array.isArray(raw.holidays)) throw new Error('Backup holiday list is malformed.');
  return normalise(raw as Partial<Data>);
}
