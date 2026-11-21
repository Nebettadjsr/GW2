import { getJson, postJson } from './http'
import { syncApi } from './syncApi'
import type { ForgeCatalogue, ForgeProject, ForgeSettings, SyncTaskAccepted, SyncTaskStatus } from './types'

/**
 * Mystic Forge routes. The catalogue is public cached metadata (no account work); the project is
 * account scoped, plans exactly one selected recipe and target, and never carries the key in a URL.
 * HTTP 202 means no account source was ever synchronized: the body then has `tree === null`.
 */
export interface MysticForgeApi {
  loadCatalogue(): Promise<ForgeCatalogue>
  loadProject(recipeId: number, target: number, settings?: ForgeSettings): Promise<ForgeProject>
  /** Existing keyed account-data refresh (no Profit simulation); the cached project read itself stays keyless. */
  startSourceRefresh?(): Promise<SyncTaskAccepted>
  readSourceTask?(statusUrl: string): Promise<SyncTaskStatus>
}

export const mysticForgeApi: MysticForgeApi = {
  loadCatalogue: () => getJson<ForgeCatalogue>('/mystic-forge/recipes'),
  loadProject: (recipeId, target, settings) => postJson<ForgeProject>('/mystic-forge/project', { recipeId, target, ...settings }),
  startSourceRefresh: () => syncApi.startProfitDataRefresh(),
  readSourceTask: (statusUrl) => syncApi.readTaskStatus(statusUrl)
}
