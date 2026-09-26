/**
 * The application's navigation destinations.
 *
 * One entry per *implemented* screen — nothing unfinished is listed, so the navigation offers no
 * dead destination (`FRONTEND_UX_GUIDELINES.md` 2). The label is used both in the navigation and as
 * the page heading, so a destination and the page it opens are named the same way everywhere.
 */
export type DestinationId = 'crafting' | 'synchronization' | 'bank' | 'materials'

export interface Destination {
  readonly id: DestinationId
  /** The in-page location this destination is reachable at, and the `href` of its navigation link. */
  readonly path: string
  readonly label: string
}

export const SITE_NAME = 'GW2 Crafting Tool'

export const DESTINATIONS: readonly Destination[] = [
  { id: 'crafting', path: '#/crafting', label: 'Crafting Profit' },
  { id: 'synchronization', path: '#/synchronization', label: 'Synchronization' },
  { id: 'bank', path: '#/bank', label: 'Bank' },
  { id: 'materials', path: '#/materials', label: 'Materials' }
]

/** Opening the application without a destination lands here. */
export const DEFAULT_DESTINATION: Destination = destinationOf('crafting')

export function destinationOf(id: DestinationId): Destination {
  const destination = DESTINATIONS.find((candidate) => candidate.id === id)
  if (destination === undefined) throw new Error(`Unknown destination: ${id}`)
  return destination
}

/** The document title of a destination; a browser tab and the history entries stay identifiable. */
export function documentTitleOf(destination: Destination): string {
  return `${destination.label} · ${SITE_NAME}`
}

/**
 * Resolves a location hash to a destination. An empty or unrecognized hash resolves to the default
 * one rather than to a blank application area — a stale or mistyped link still opens a real screen.
 */
export function destinationForHash(hash: string): Destination {
  const normalized = hash.startsWith('#') ? hash : `#${hash}`
  return DESTINATIONS.find((candidate) => candidate.path === normalized) ?? DEFAULT_DESTINATION
}
