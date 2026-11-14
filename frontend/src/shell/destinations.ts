/**
 * The application's navigation destinations.
 *
 * One entry per *implemented* screen — nothing unfinished is listed, so the navigation offers no
 * dead destination (`FRONTEND_UX_GUIDELINES.md` 2). The label is used both in the navigation and as
 * the page heading, so a destination and the page it opens are named the same way everywhere.
 */
export type DestinationId =
  | 'crafting'
  | 'discovery'
  | 'ecto'
  | 'synchronization'
  | 'bank'
  | 'materials'
  | 'characters'
  | 'legal'
  | 'privacy'
  | 'storage'

export interface Destination {
  readonly id: DestinationId
  /** The in-page location this destination is reachable at, and the `href` of its navigation link. */
  readonly path: string
  readonly label: string
}

export const SITE_NAME = 'GW2 Crafting Tool'

export const DESTINATIONS: readonly Destination[] = [
  { id: 'crafting', path: '#/crafting', label: 'Crafting Profit' },
  { id: 'discovery', path: '#/discovery', label: 'Crafting Discovery' },
  { id: 'ecto', path: '#/ecto', label: 'Ecto Salvage' },
  { id: 'synchronization', path: '#/synchronization', label: 'System Status' },
  { id: 'bank', path: '#/bank', label: 'Bank' },
  { id: 'materials', path: '#/materials', label: 'Materials' },
  { id: 'characters', path: '#/characters', label: 'Characters' }
]

/**
 * The public information pages, linked from the footer and deliberately not from the main
 * navigation. They are readable without a session and never read account data.
 */
export const INFO_DESTINATIONS: readonly Destination[] = [
  { id: 'legal', path: '#/legal', label: 'Legal notice' },
  { id: 'privacy', path: '#/privacy', label: 'Privacy' },
  { id: 'storage', path: '#/storage', label: 'Cookies & browser storage' }
]

/** Whether a destination is a public information page rather than an application area. */
export function isInfoDestination(destination: Destination): boolean {
  return INFO_DESTINATIONS.some((candidate) => candidate.id === destination.id)
}

/** Opening the application without a destination lands here. */
export const DEFAULT_DESTINATION: Destination = destinationOf('crafting')

export function destinationOf(id: DestinationId): Destination {
  const destination = [...DESTINATIONS, ...INFO_DESTINATIONS].find((candidate) => candidate.id === id)
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
  return [...DESTINATIONS, ...INFO_DESTINATIONS].find((candidate) => candidate.path === normalized) ?? DEFAULT_DESTINATION
}
