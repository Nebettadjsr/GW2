/**
 * Which application area is open, kept in the address bar.
 *
 * The destination lives in the location hash, so every screen has a real URL, the browser's Back and
 * Forward buttons move between the screens that were visited, and a link or bookmark opens the screen
 * it names. No router library is added for this: the application has one flat set of destinations and
 * no nested or parameterized routes (`TARGET_ARCHITECTURE.md` 4.1 keeps framework choices minimal).
 *
 * Only the *destination* is addressable. Nothing else is written to the URL and nothing is restored
 * from it, so a reload opens the named screen with no state carried over — in particular no
 * synchronization tracking, which this client deliberately does not persist.
 */
import { onScopeDispose, ref, watch, type Ref } from 'vue'
import {
  destinationForHash,
  destinationOf,
  documentTitleOf,
  type Destination,
  type DestinationId
} from './destinations'

export interface HashRoute {
  /** The open destination. */
  readonly current: Readonly<Ref<Destination>>
  /** Opens a destination, which is also what its navigation link does. Submits nothing. */
  navigate(id: DestinationId): void
}

export function useHashRoute(): HashRoute {
  const current = ref<Destination>(destinationForHash(window.location.hash))

  // A hash that named no destination is corrected in place, so the address bar cannot claim to be
  // somewhere the application is not. `replaceState` keeps this out of the history.
  if (window.location.hash !== current.value.path) {
    window.history.replaceState(null, '', current.value.path)
  }

  /**
   * Applied directly rather than awaited from the `hashchange` event: the click that opened a
   * destination has already happened, and the listener below reconciles the same value when the
   * browser's own navigation (Back, Forward, a typed URL) is the source.
   */
  function navigate(id: DestinationId): void {
    const destination = destinationOf(id)
    current.value = destination
    if (window.location.hash !== destination.path) window.location.hash = destination.path
  }

  function onHashChange(): void {
    current.value = destinationForHash(window.location.hash)
  }

  window.addEventListener('hashchange', onHashChange)
  onScopeDispose(() => window.removeEventListener('hashchange', onHashChange))

  watch(current, (destination) => (document.title = documentTitleOf(destination)), {
    immediate: true
  })

  return { current, navigate }
}
