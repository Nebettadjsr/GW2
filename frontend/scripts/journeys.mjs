/**
 * The three PROVISIONAL / UNOBSERVED actual-app journeys (STORY-RESOURCE-002): Crafting, Bank/Materials and
 * Characters. They drive the unmodified browser UI through its stable `data-test` hooks, are read-only with
 * respect to the game account, and complete an action only on a rendered checkpoint that holds for the stable
 * interval (never `networkidle`). Until the owner replaces them with recordings they are not representative
 * user behavior, and their dwell values are editable placeholders marked `unobserved`.
 */
import { CHARACTER_SECTIONS, STORAGE_PREFIX, hashText, shortText } from './journeyCommon.mjs'

/**
 * Init script installed in every context before the application loads. It only OBSERVES the saved-selection
 * write path (counts `localStorage` writes under the Material Protection prefix) and, as a second line of
 * defence, swallows any click on the "Save selection" control. The journeys never click it themselves.
 */
export const WRITE_GUARD_SCRIPT = (prefix) => `(() => {
  const guard = (window.__gw2JourneyGuard = { writes: 0, blockedCommitClicks: 0 })
  const prefix = ${JSON.stringify(prefix)}
  const proto = Storage.prototype
  for (const name of ['setItem', 'removeItem']) {
    const original = proto[name]
    proto[name] = function (key, ...rest) {
      if (typeof key === 'string' && key.startsWith(prefix)) guard.writes += 1
      return original.call(this, key, ...rest)
    }
  }
  const clear = proto.clear
  proto.clear = function () { guard.writes += 1; return clear.call(this) }
  document.addEventListener('click', (event) => {
    const target = event.target
    if (target && target.closest && target.closest('[data-test="selection-confirm"]')) {
      guard.blockedCommitClicks += 1
      event.preventDefault()
      event.stopImmediatePropagation()
    }
  }, true)
})()`

const sel = (id) => `[data-test="${id}"]`

const visible = (page, id) => page.isVisible(sel(id)).catch(() => false)
const anyVisible = async (page, ids) => { for (const id of ids) if (await visible(page, id)) return true; return false }
const text = (page, id) => page.textContent(sel(id)).then((t) => shortText(t)).catch(() => '')

/** The intended route and page heading are present. */
async function onPage(page, hash, headingPart) {
  const current = await page.evaluate(() => window.location.hash).catch(() => '')
  if (current !== hash) return false
  return (await text(page, 'page-heading')).includes(headingPart)
}

async function navigate(page, origin, hash) {
  await page.goto(`${origin}/${hash}`)
}

/** Opaque digest of the saved Material Protection preference (values never leave the page). */
async function savedSelectionDigest(page) {
  const entries = await page.evaluate((prefix) => {
    const found = []
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i)
      if (key !== null && key.startsWith(prefix)) found.push([key, localStorage.getItem(key)])
    }
    return found.sort((a, b) => (a[0] < b[0] ? -1 : 1))
  }, STORAGE_PREFIX)
  return { entries: entries.length, digest: hashText(JSON.stringify(entries)) }
}

const guardCounters = (page) => page.evaluate(() => ({ ...(window.__gw2JourneyGuard ?? { writes: -1, blockedCommitClicks: -1 }) }))

export async function craftingFlow(user, options) {
  const { page } = user
  const hash = '#/crafting'
  const busy = ['loading', 'pending', 'resolution-loading']
  const resultsShown = (ids) => anyVisible(page, ids)
  await user.action({
    name: 'open-crafting', description: 'Open the Crafting Profit destination',
    initial: async () => visible(page, 'profit-row'),
    perform: () => navigate(page, options.origin, hash),
    final: async () => (await onPage(page, hash, 'Crafting')) && !(await anyVisible(page, busy)) &&
      (await resultsShown(['profit-row', 'empty', 'no-matches'])) && (await page.isEnabled(sel('refresh-data-and-results')).catch(() => false))
  })
  const before = await text(page, 'summary')
  await user.action({
    name: 'recalculate', description: 'Run the provisional calculation control and wait for the new result', dwell: true,
    initial: async () => visible(page, 'profit-row'),
    perform: () => page.click(sel('refresh-data-and-results')),
    // The result belongs to THIS action only when an API response to a request started after the click completed.
    final: async (performedAt) => (await onPage(page, hash, 'Crafting')) && !(await anyVisible(page, busy)) &&
      user.requests.items.some((r) => r.path.startsWith('/api/') && r.completed && r.startOffsetMs >= user.offset(performedAt)) &&
      (await resultsShown(['profit-row', 'empty', 'no-matches'])) && (await page.isEnabled(sel('refresh-data-and-results')).catch(() => false)),
    outcome: async () => ({ summaryChanged: (await text(page, 'summary')) !== before, summaryBefore: before === '' ? null : 'present' })
  })
  if (!(await visible(page, 'profit-row'))) {
    await user.action({
      name: 'inspect-result', description: 'Result detail interaction (no result row is available)', optional: true,
      perform: async () => { throw new Error('no result row is rendered, so the detail interaction is unsupported on this account') },
      final: async () => false
    })
    return { sections: [], note: 'detail interaction unsupported: no result rows' }
  }
  let rowName = ''
  await user.action({
    name: 'inspect-result', description: 'Select the first result row and wait for its detail', dwell: true,
    perform: async () => {
      const button = page.locator(`${sel('profit-row')} button`).first()
      rowName = shortText(await button.textContent())
      await button.click()
    },
    final: async () => (await visible(page, 'detail-name')) && !(await visible(page, 'detail-loading')) &&
      rowName !== '' && (await text(page, 'detail-name')).includes(rowName.slice(0, 20)),
    outcome: async () => ({ detailMatchesSelectedRow: true })
  })
  return { sections: ['crafting-detail'] }
}

export async function bankMaterialsFlow(user, options) {
  const { page } = user
  const violations = []
  await user.action({
    name: 'open-bank', description: 'Open Bank and wait for inventory content',
    initial: async () => visible(page, 'bank-slot'),
    perform: () => navigate(page, options.origin, '#/bank'),
    final: async () => (await onPage(page, '#/bank', 'Bank')) && !(await visible(page, 'bank-loading')) && (await anyVisible(page, ['bank-slot', 'bank-no-slots']))
  })
  const materialsReady = async () => (await onPage(page, '#/materials', 'Materials')) && !(await visible(page, 'materials-loading')) &&
    (await anyVisible(page, ['material-category', 'materials-empty']))
  await user.action({
    name: 'open-materials', description: 'Navigate Bank → Materials and wait for categories',
    perform: () => page.click(sel('nav-materials')), final: materialsReady
  })

  const baseline = await savedSelectionDigest(page)
  const guardBefore = await guardCounters(page)
  const pendingState = async () => (await page.getAttribute(sel('selection-state'), 'data-state').catch(() => null)) === 'pending'
  const checkbox = () => page.locator(sel('protect-checkbox')).first()
  let initiallyChecked = null

  await user.action({
    name: 'change-selection-pending', description: 'Toggle one protection checkbox; never saved', dwell: true,
    perform: async () => {
      if (!(await checkbox().isVisible().catch(() => false)) && (await visible(page, 'category-toggle'))) await page.locator(sel('category-toggle')).first().click()
      await checkbox().waitFor({ state: 'visible', timeout: options.timeoutMs })
      initiallyChecked = await checkbox().isChecked()
      await checkbox().click()
    },
    final: async () => (await pendingState()) && (await page.isEnabled(sel('selection-discard')).catch(() => false)) && (await checkbox().isChecked()) !== initiallyChecked
  })
  await user.action({
    name: 'navigate-with-pending', description: 'Bank → Materials navigation while the selection stays pending', dwell: true,
    perform: async () => { await page.click(sel('nav-bank')); await page.waitForTimeout(100); await page.click(sel('nav-materials')) },
    final: async () => (await materialsReady()) && (await pendingState()) && (await page.isEnabled(sel('selection-discard')).catch(() => false))
  })
  await user.action({
    name: 'discard-pending', description: 'Discard the pending change (never Save selection)',
    perform: () => page.click(sel('selection-discard')),
    final: async () => !(await pendingState()) && (await checkbox().isChecked().catch(() => null)) === initiallyChecked
  })

  const after = await savedSelectionDigest(page)
  const guardAfter = await guardCounters(page)
  const writesDelta = guardAfter.writes - guardBefore.writes
  if (after.digest !== baseline.digest || after.entries !== baseline.entries) violations.push('saved selection differs from the committed value observed before the journey')
  if (writesDelta !== 0) violations.push(`saved-selection write path was used ${writesDelta} time(s)`)
  if (guardAfter.blockedCommitClicks !== 0) violations.push('a Save selection click was attempted and blocked')
  return {
    safety: {
      violations, commitControlInvoked: false, blockedCommitClicks: guardAfter.blockedCommitClicks, savedSelectionWritesDuringJourney: writesDelta,
      savedSelectionUnchanged: after.digest === baseline.digest, savedEntriesBefore: baseline.entries, savedEntriesAfter: after.entries,
      comparison: 'sha256 prefix of the serialized saved preference; values are never recorded'
    }
  }
}

export async function charactersFlow(user, options) {
  const { page } = user
  const failed = ['roster-error']
  await user.action({
    name: 'open-characters', description: 'Open Characters and wait for the rendered roster',
    initial: async () => visible(page, 'roster-row'),
    perform: () => navigate(page, options.origin, '#/characters'),
    final: async () => (await onPage(page, '#/characters', 'Characters')) && !(await visible(page, 'roster-loading')) && (await anyVisible(page, ['roster-row', 'roster-empty', ...failed])),
    outcome: async () => ((await visible(page, 'roster-row')) ? 'roster-rendered' : (await visible(page, 'roster-error')) ? 'roster-unavailable' : 'roster-empty')
  })
  if (!(await visible(page, 'roster-row'))) return { sections: [], note: 'roster unavailable or empty; no character selected' }
  let name = ''
  await user.action({
    name: 'select-character', description: 'Select the first character; identity must render', dwell: true,
    perform: async () => {
      const item = page.locator(sel('roster-item')).first()
      name = shortText(await item.getAttribute('data-name'))
      await item.click()
    },
    final: async () => name !== '' && (await visible(page, 'character-detail')) && (await text(page, 'page-heading')).includes(name)
  })
  const rendered = []
  for (const section of options.sections.filter((s) => CHARACTER_SECTIONS.includes(s))) {
    await user.action({
      name: `section-${section}`, description: `Open the ${section} section of the selected character`, dwell: true, optional: true,
      perform: () => page.click(sel(`section-${section}`)),
      final: async () => (await page.getAttribute(sel(`section-${section}`), 'aria-selected').catch(() => null)) === 'true' &&
        (await text(page, 'page-heading')).includes(name) && !(await visible(page, `${section}-loading`)) &&
        (await anyVisible(page, [`${section}-panel`, `${section}-error`])),
      // Optional detail that is unavailable is an observed result, not a roster failure.
      outcome: async () => ((await visible(page, `${section}-error`)) ? 'unavailable-optional-detail' : 'rendered')
    })
    rendered.push(section)
  }
  return { sections: rendered }
}

export const JOURNEY_FLOWS = Object.freeze({ crafting: craftingFlow, 'bank-materials': bankMaterialsFlow, characters: charactersFlow })
