import type { AccountApi } from '@/api/accountApi'
import type { BankContents, MaterialStorage } from '@/api/types'

/**
 * Controlled responses for the account-read screens (`CURRENT_ARCHITECTURE.md` 5.12).
 *
 * The values are deliberately awkward: the categories are not in alphabetical order, the stacks are
 * not ordered by count or item id, the same item id appears in two categories, and both a supplied
 * and an absent `iconUrl` occur. A screen that sorted, regrouped or deduplicated would therefore
 * fail these tests rather than pass them by accident.
 *
 * The supplied URLs are application-relative, exactly as the backend emits them
 * (`TARGET_ARCHITECTURE.md` 12.1): no fixture carries an upstream URL or a filesystem path, because
 * the contract no longer has a field either could arrive in.
 */

const COPPER_ORE_ICON_URL =
  '/api/items/19697/icon/50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472.png'
const GARLIC_ICON_URL =
  '/api/items/12134/icon/9247ad5f6cd4702a0c12724f8a90235191e8831a61be53d54a138b86e3984f8d.jpg' 

/** A bank whose empty slots sit between occupied ones, so order and position are observable. */
export const bankWithEmptySlots: BankContents = {
  slotCount: 5,
  slots: [
    { slot: 0, itemId: 19697, count: 42, iconUrl: COPPER_ORE_ICON_URL, rarity: 'Basic' },
    // Empty: both nulls, and neither may be read as item id 0 or as an owned count of 0.
    { slot: 1, itemId: null, count: null, iconUrl: null, rarity: null },
    // Occupied but without display metadata of any kind.
    { slot: 2, itemId: 24295, count: 1, iconUrl: null, rarity: null },
    { slot: 3, itemId: null, count: null, iconUrl: null, rarity: null },
    { slot: 4, itemId: 12134, count: 250, iconUrl: GARLIC_ICON_URL, rarity: 'Fine' }
  ]
}

/** A successful read of a bank that has no slots at all — not a bank whose slots are empty. */
export const bankWithoutSlots: BankContents = { slotCount: 0, slots: [] }

/**
 * Two categories in the backend's order, the second carrying its `"Category <id>"` fallback label.
 * Item 12134 appears in both, and the first category's stacks ascend by count.
 */
export const materialStorage: MaterialStorage = {
  categoryCount: 2,
  categories: [
    {
      name: 'Zephyrite Supplies',
      materials: [
        { category: 30, itemId: 12134, count: 3, iconUrl: GARLIC_ICON_URL, rarity: 'Fine' },
        { category: 30, itemId: 19697, count: 250, iconUrl: null, rarity: null }
      ]
    },
    {
      name: 'Category 77',
      materials: [
        { category: 77, itemId: 12134, count: 11, iconUrl: null, rarity: 'Rare' },
        // A stored row the backend reported without an item id; its count is still supplied.
        { category: 77, itemId: null, count: 7, iconUrl: null, rarity: null }
      ]
    }
  ]
}

/** A successful read of material storage holding nothing. */
export const emptyMaterialStorage: MaterialStorage = { categoryCount: 0, categories: [] }

/** A promise a test resolves by hand, for in-flight, out-of-order and navigation checks. */
export interface Deferred<T> {
  promise: Promise<T>
  resolve(value: T): void
  reject(cause: unknown): void
}

export function deferred<T>(): Deferred<T> {
  let resolve: (value: T) => void = () => undefined
  let reject: (cause: unknown) => void = () => undefined
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

/** Records every read and answers from handlers the test supplies. */
export class FakeAccountApi implements AccountApi {
  bankCalls = 0
  materialCalls = 0
  bankHandler: (callIndex: number) => Promise<BankContents> = () =>
    Promise.resolve(bankWithEmptySlots)
  materialsHandler: (callIndex: number) => Promise<MaterialStorage> = () =>
    Promise.resolve(materialStorage)

  loadBank(): Promise<BankContents> {
    return this.bankHandler(this.bankCalls++)
  }

  loadMaterials(): Promise<MaterialStorage> {
    return this.materialsHandler(this.materialCalls++)
  }
}
