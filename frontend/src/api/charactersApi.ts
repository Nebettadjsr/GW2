import { getStateJson, postJson } from './http'
import type { CharacterBuildTabsResponse, CharacterEquipmentTabsResponse, CharacterInventoryResponse, CharacterReadResponse, SyncTaskAccepted } from './types'

/**
 * The Characters read routes and the one equipment-tabs refresh trigger. Character names are the
 * request identity and are path-encoded. Reads return the backend's state body even for 403/404/503
 * so the state and reason are preserved; nothing here contacts the GW2 API.
 */
export interface CharactersApi {
  loadRoster(): Promise<CharacterReadResponse>
  loadCharacter(name: string): Promise<CharacterReadResponse>
  loadEquipmentTabs(name: string): Promise<CharacterEquipmentTabsResponse>
  loadInventory(name: string): Promise<CharacterInventoryResponse>
  /** Selected character's saved build tabs (optional BUILD_TABS coverage); used for the header's active elite label. */
  loadBuildTabs(name: string): Promise<CharacterBuildTabsResponse>
  /** The existing optional-source refresh for build tabs; takes no body fields. */
  startBuildTabsRefresh(): Promise<SyncTaskAccepted>
  /** The existing optional-source refresh; takes no body fields and carries the transient key. */
  startEquipmentTabsRefresh(): Promise<SyncTaskAccepted>
}

export const charactersApi: CharactersApi = {
  loadRoster: () => getStateJson<CharacterReadResponse>('/account/characters'),
  loadCharacter: (name) => getStateJson<CharacterReadResponse>(`/account/characters/${encodeURIComponent(name)}`),
  loadEquipmentTabs: (name) =>
    getStateJson<CharacterEquipmentTabsResponse>(`/account/characters/${encodeURIComponent(name)}/equipment-tabs`),
  loadInventory: (name) =>
    getStateJson<CharacterInventoryResponse>(`/account/characters/${encodeURIComponent(name)}/inventory`),
  loadBuildTabs: (name) =>
    getStateJson<CharacterBuildTabsResponse>(`/account/characters/${encodeURIComponent(name)}/build-tabs`),
  startBuildTabsRefresh: () => postJson<SyncTaskAccepted>('/sync/account/character-build-tabs', {}),
  startEquipmentTabsRefresh: () => postJson<SyncTaskAccepted>('/sync/account/character-equipment-tabs', {})
}
