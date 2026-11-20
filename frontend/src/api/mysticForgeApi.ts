import { getJson, postJson } from './http'
import { syncApi } from './syncApi'
import type { ForgeCatalogue, ForgeProject, SyncTaskAccepted, SyncTaskStatus } from './types'

/**
 * Mystic Forge routes. The catalogue is public cached metadata (no account work); the project is
 * account scoped, plans exactly one selected recipe and target, and never carries the key in a URL.
 * HTTP 202 means no account source was ever synchronized: the body then has `tree === null`.
 */
export interface MysticForgeApi {
  loadCatalogue(): Promise<ForgeCatalogue>
  loadProject(recipeId: number, target: number): Promise<ForgeProject>
  /** Existing keyed account-data refresh (no Profit simulation); the cached project read itself stays keyless. */
  startSourceRefresh?(): Promise<SyncTaskAccepted>
  readSourceTask?(statusUrl: string): Promise<SyncTaskStatus>
}

export const mysticForgeApi: MysticForgeApi = {
  loadCatalogue: () => getJson<ForgeCatalogue>('/mystic-forge/recipes'),
  loadProject: (recipeId, target) => postJson<ForgeProject>('/mystic-forge/project', { recipeId, target }),
  startSourceRefresh: () => syncApi.startProfitDataRefresh(),
  readSourceTask: (statusUrl) => syncApi.readTaskStatus(statusUrl)
}
