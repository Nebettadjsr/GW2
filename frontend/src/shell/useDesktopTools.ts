import { onScopeDispose, ref, type Ref } from 'vue'

/** The narrowest viewport, in CSS pixels, on which the account tools are supported (owner decision in DOMAIN_SPEC). */
export const MIN_TOOL_WIDTH_PX = 1024

const QUERY = `(min-width: ${MIN_TOOL_WIDTH_PX}px)`

/**
 * Whether the viewport is wide enough for the account tools. Uses the viewport capability
 * (`matchMedia`), never the user agent, and follows live resizes. Without `matchMedia` the width
 * falls back to `innerWidth`, and a missing capability counts as supported.
 */
export function useDesktopTools(): Readonly<Ref<boolean>> {
  const supported = ref(true)
  if (typeof window === 'undefined') return supported
  if (typeof window.matchMedia !== 'function') {
    supported.value = window.innerWidth >= MIN_TOOL_WIDTH_PX
    const onResize = (): void => { supported.value = window.innerWidth >= MIN_TOOL_WIDTH_PX }
    window.addEventListener('resize', onResize)
    onScopeDispose(() => window.removeEventListener('resize', onResize))
    return supported
  }
  const list = window.matchMedia(QUERY)
  supported.value = list.matches
  const onChange = (event: MediaQueryListEvent): void => { supported.value = event.matches }
  list.addEventListener('change', onChange)
  onScopeDispose(() => list.removeEventListener('change', onChange))
  return supported
}
