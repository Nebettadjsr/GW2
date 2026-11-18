/**
 * Which application area is open, kept in the address bar.
 *
 * Tool destinations live in the location hash; Home has its own /welcome document so its indexing
 * policy can differ from account pages. Every screen has a real URL, the browser's Back and
 * Forward buttons move between the screens that were visited, and a link or bookmark opens the screen
 * it names. No router library is added for this: the application has one flat set of destinations and
 * no nested or parameterized routes (`ARCHITECTURE.md` keeps framework choices minimal).
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

  function pathOf(destination: Destination): string {
    return destination.id === 'home' ? destination.path : `/${destination.path}`
  }

  // The default Home page uses its public URL; existing tool bookmarks retain their hashes.
  if (window.location.pathname + window.location.hash !== pathOf(current.value)) {
    window.history.replaceState(null, '', pathOf(current.value))
  }

  /**
   * Applied directly rather than awaited from a location event: the click that opened a
   * destination has already happened, and the listener below reconciles the same value when the
   * browser's own navigation (Back, Forward, a typed URL) is the source.
   */
  function navigate(id: DestinationId): void {
    const destination = destinationOf(id)
    current.value = destination
    if (window.location.pathname + window.location.hash !== pathOf(destination)) {
      window.history.pushState(null, '', pathOf(destination))
      // App and AccountWorkspace each observe the route; pushState emits no native event.
      window.dispatchEvent(new PopStateEvent('popstate'))
    }
  }

  function onHashChange(): void {
    // A historical Home skip-link anchor still names Home when Back changes the document path.
    if (window.location.hash === '#main-content' && window.location.pathname !== '/welcome') return
    current.value = destinationForHash(window.location.hash)
  }

  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('popstate', onHashChange)
  onScopeDispose(() => {
    window.removeEventListener('hashchange', onHashChange)
    window.removeEventListener('popstate', onHashChange)
  })

  watch(current, (destination) => {
    document.title = documentTitleOf(destination)
    document.querySelector('meta[name="robots"]')?.setAttribute('content',
      destination.id === 'home' ? 'index, follow' : 'noindex, nofollow')
  }, {
    immediate: true
  })

  return { current, navigate }
}
