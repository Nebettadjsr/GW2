import type { MaterialProtectionState, SelectionView } from '../materialProtectionState'

/**
 * Fixture setup through the real autosave actions: makes `ids` the exact stored selection of the given
 * view (both views when omitted) with at most one removal write and one addition write per view.
 */
export function choose(state: MaterialProtectionState, ids: readonly number[], view?: SelectionView): void {
  for (const v of view === undefined ? (['bank', 'materials'] as const) : [view]) {
    const current = new Set(state.committedFor(v))
    const target = new Set(ids)
    const remove = [...current].filter(id => !target.has(id))
    const add = [...target].filter(id => !current.has(id))
    if (remove.length > 0) state.togglePendingGroup(remove, v)
    if (add.length > 0) state.togglePendingGroup(add, v)
  }
}
