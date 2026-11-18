import { onScopeDispose, ref, type Ref } from 'vue'

/** The tool layout baseline, in CSS pixels. Below it a notice is shown; it never gates account work. */
export const TOOL_LAYOUT_WIDTH_PX = 1024

const QUERY = `(max-width: ${TOOL_LAYOUT_WIDTH_PX - 0.02}px)`

/**
 * Whether the viewport is narrower than the tool layout baseline. Uses the viewport capability
 * (`matchMedia`), never the user agent, and follows live resizes. Without `matchMedia` it falls back
 * to `innerWidth`.
 */
export function useNarrowViewport(): Readonly<Ref<boolean>> {
  const narrow = ref(false)
  if (typeof window === 'undefined') return narrow
  const measure = (): boolean => typeof window.matchMedia === 'function'
    ? window.matchMedia(QUERY).matches
    : window.innerWidth < TOOL_LAYOUT_WIDTH_PX
  narrow.value = measure()
  // `resize` is delivered before media-query change listeners, so the notice follows a width change
  // without waiting for the later notification; the media query remains the measure.
  const update = (): void => { narrow.value = measure() }
  window.addEventListener('resize', update)
  onScopeDispose(() => window.removeEventListener('resize', update))
  return narrow
}
