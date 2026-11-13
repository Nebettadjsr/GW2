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

const PROFIT_PATH = '/api/crafting/profit'

export async function craftingFlow(user, options) {
  const { page } = user
  const hash = '#/crafting'
  const busy = ['loading', 'pending', 'resolution-loading']
  const resultsShown = () => anyVisible(page, ['profit-row', 'empty', 'no-matches'])
  const refreshEnabled = () => page.isEnabled(sel('refresh-data-and-results')).catch(() => false)
  const setting = sel('setting-listingSell')
  await user.action({
    name: 'open-crafting', description: 'Open the Crafting Profit destination',
    initial: async () => visible(page, 'profit-row'),
    perform: () => navigate(page, options.origin, hash),
    final: async () => (await onPage(page, hash, 'Crafting')) && !(await anyVisible(page, busy)) &&
      (await resultsShown()) && (await visible(page, 'setting-listingSell')) && (await refreshEnabled())
  })
  const original = await page.inputValue(setting).catch(() => null)
  if (original === null) {
    user.skip('change-setting', 'the calculation settings control is not rendered')
    user.skip('inspect-result', 'no calculation result is available')
    return { sections: [], note: 'calculation setting control unavailable' }
  }
  // A real calculation-setting change (not a broad account refresh). It is complete only when THIS change's
  // calculation request, offered after the click, has answered AND the page shows the new setting with usable results.
  const changeTo = async (name, description, target, dwell) => user.action({
    name, description, dwell,
    initial: async () => visible(page, 'profit-row'),
    perform: () => page.selectOption(setting, target),
    final: async (performedAt) => (await onPage(page, hash, 'Crafting')) && (await page.inputValue(setting).catch(() => null)) === target &&
      user.matchRequests({ method: 'POST', path: PROFIT_PATH, sinceMono: performedAt }).length > 0 &&
      !(await anyVisible(page, busy)) && (await resultsShown()) && (await refreshEnabled()),
    outcome: async () => ({ setting: 'sell price', from: original, to: target })
  })
  const target = original === 'listing' ? 'instant' : 'listing'
  await changeTo('change-setting', 'Switch the sell-price calculation setting and wait for its own result', target, true)
  let rowName = ''
  if (!(await visible(page, 'profit-row'))) {
    user.skip('inspect-result', 'no result row is rendered, so the detail interaction is unsupported on this account')
  } else {
    await user.action({
      name: 'inspect-result', description: 'Select the first result row and wait for its detail', dwell: true,
      perform: async () => {
        const row = page.locator(sel('profit-row')).first()
        rowName = shortText(await row.locator('.recipe-name').first().textContent())
        await row.locator(sel('select-row')).click()
      },
      final: async () => rowName !== '' && (await visible(page, 'detail-name')) && !(await anyVisible(page, ['detail-loading', 'resolution-loading'])) &&
        (await text(page, 'detail-name')).includes(rowName.slice(0, 20)),
      outcome: async () => ({ detailMatchesSelectedRow: true })
    })
  }
  await changeTo('restore-setting', 'Put the sell-price setting back to the value found at the start', original, false)
  return { sections: rowName === '' ? [] : ['crafting-detail'] }
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
  const bankReady = async () => (await onPage(page, '#/bank', 'Bank')) && !(await visible(page, 'bank-loading')) && (await anyVisible(page, ['bank-slot', 'bank-no-slots']))
  await user.action({
    name: 'return-to-bank', description: 'Navigate Materials → Bank while the selection stays pending', dwell: true,
    perform: () => page.click(sel('nav-bank')), final: bankReady
  })
  await user.action({
    name: 'return-to-materials', description: 'Navigate Bank → Materials; the pending selection must still be there', dwell: true,
    perform: () => page.click(sel('nav-materials')),
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


/** The actual hooks of each Characters section; the first group that is visible names the observed outcome. */
const SECTION_HOOKS = Object.freeze({
  equipment: { loading: 'equipment-loading', failed: ['equipment-error'], unavailable: ['equipment-state'], empty: ['equipment-empty', 'equipment-no-tabs'], rendered: ['equipment-panel'] },
  inventory: { loading: 'inventory-loading', failed: ['inventory-error'], unavailable: ['inventory-state'], empty: ['inventory-no-bags', 'inventory-all-empty'], rendered: ['inventory-panel', 'inventory-grid'] },
  builds: { loading: 'builds-loading', failed: ['builds-error'], unavailable: ['builds-state'], empty: ['builds-none'], rendered: ['builds-panel'] }
})

/** `rendered | empty | unavailable | failed`, or null while still loading or when no known hook is shown (never a guess). */
export async function sectionOutcome(page, section) {
  const hooks = SECTION_HOOKS[section]
  if (await visible(page, hooks.loading)) return null
  for (const kind of ['failed', 'unavailable', 'empty', 'rendered']) if (await anyVisible(page, hooks[kind])) return kind
  return null
}

export async function charactersFlow(user, options) {
  const { page } = user
  const failed = ['roster-error', 'roster-state']
  await user.action({
    name: 'open-characters', description: 'Open Characters and wait for the rendered roster',
    initial: async () => visible(page, 'roster-row'),
    perform: () => navigate(page, options.origin, '#/characters'),
    final: async () => (await onPage(page, '#/characters', 'Characters')) && !(await visible(page, 'roster-loading')) && (await anyVisible(page, ['roster-row', 'roster-empty', ...failed])),
    outcome: async () => ((await visible(page, 'roster-row')) ? 'roster-rendered' : (await anyVisible(page, ['roster-error', 'roster-state'])) ? 'roster-unavailable' : 'roster-empty')
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
  const sectionOutcomes = {}
  for (const section of options.sections.filter((x) => CHARACTER_SECTIONS.includes(x))) {
    if (!(await visible(page, `section-${section}`))) {
      user.skip(`section-${section}`, 'the section tab is not rendered').outcome = 'missing'
      sectionOutcomes[section] = 'missing'
      continue
    }
    const record = await user.action({
      name: `section-${section}`, description: `Open the ${section} section of the selected character`, dwell: true, optional: true,
      perform: () => page.click(sel(`section-${section}`)),
      final: async () => (await page.getAttribute(sel(`section-${section}`), 'aria-selected').catch(() => null)) === 'true' &&
        (await text(page, 'page-heading')).includes(name) && (await sectionOutcome(page, section)) !== null,
      // missing/empty/unavailable/failed are observed results of OPTIONAL detail, never a roster failure; a timeout is not one of them.
      outcome: () => sectionOutcome(page, section)
    })
    sectionOutcomes[section] = record.terminal === 'completed' ? record.outcome : record.terminal
    rendered.push(section)
  }
  return { sections: rendered, sectionOutcomes }
}

export const JOURNEY_FLOWS = Object.freeze({ crafting: craftingFlow, 'bank-materials': bankMaterialsFlow, characters: charactersFlow })
