import { getJson } from './http'
import type { BankContents, MaterialStorage } from './types'

/**
 * The two account-read routes the Bank and Materials screens use
 * (`CURRENT_ARCHITECTURE.md` 5.12).
 *
 * Both are plain GETs with no request input. Neither starts a synchronization, and nothing here
 * contacts the GW2 API — the backend owns that path entirely.
 *
 * An interface rather than bare functions so a test can supply controlled responses without
 * stubbing the global `fetch`.
 */
export interface AccountApi {
  loadBank(): Promise<BankContents>
  loadMaterials(): Promise<MaterialStorage>
}

export const accountApi: AccountApi = {
  loadBank(): Promise<BankContents> {
    return getJson<BankContents>('/account/bank')
  },

  loadMaterials(): Promise<MaterialStorage> {
    return getJson<MaterialStorage>('/account/materials')
  }
}
