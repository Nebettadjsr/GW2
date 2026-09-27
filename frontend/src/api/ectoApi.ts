import { getJson } from './http'
import type { EctoSalvage } from './types'

/**
 * The one Ectoplasm Salvage route the screen uses (`CURRENT_ARCHITECTURE.md` 5.15).
 *
 * A plain GET with no request input: the backend use case takes no parameters, so there is no yield,
 * fee, item or acquisition mode for this client to send. Nothing here contacts the GW2 API — the
 * backend owns the live Trading Post lookup entirely — and nothing here starts a synchronization.
 *
 * An interface rather than a bare function so a test can supply controlled responses without
 * stubbing the global `fetch`.
 */
export interface EctoApi {
  loadSalvage(): Promise<EctoSalvage>
}

export const ectoApi: EctoApi = {
  loadSalvage(): Promise<EctoSalvage> {
    return getJson<EctoSalvage>('/ecto/salvage')
  }
}
