import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EctoSalvageScreen from '../EctoSalvageScreen.vue'
import {
  accountLuck,
  cappedAccountLuck,
  DUST_ID,
  ECTO_ID,
  itemMetadata,
  itemPrices,
  METADATA_IDS
} from './currentEctoFixtures'
import { withoutDigitGrouping } from './renderedNumbers'

interface RecordedRequest { method: string; url: string }
const requests: RecordedRequest[] = []
let luckAnswer: unknown = accountLuck
let pricesAnswer: unknown = itemPrices
let priceFailure = false
let luckStored = true
let taskState: 'SUCCEEDED' | 'FAILED' = 'SUCCEEDED'
let admissionCoverage: boolean[] = []
let lastAdmissionCovered = true
let stallAdmission = false
let taskLookupDenied = false
let wrapper: VueWrapper | null = null

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  })
}

async function openScreen(): Promise<VueWrapper> {
  wrapper = mount(EctoSalvageScreen)
  await flushPromises()
  return wrapper
}

function buttonIn(open: VueWrapper, selector: string, label: string) {
  const button = open.findAll(selector).find((candidate) => candidate.text().includes(label))
  if (!button) throw new Error(`Missing ${label} button in ${selector}`)
  return button
}

/**
 * A rendered region's text with locale digit grouping removed, so every Luck and Gem expectation
 * below names the number the screen was asked to show instead of the runner's default separator.
 */
function ungrouped(region: { text: () => string }): string {
  return withoutDigitGrouping(region.text())
}

/** One labelled figure of the salvage calculation, addressed by the label it is filed under. */
function calculationFigure(open: VueWrapper, label: string) {
  const row = open
    .findAll(
      '[data-test="ecto-calculation"] .calculation-row, [data-test="ecto-calculation"] .calculation-total'
    )
    .find((candidate) => candidate.find('span').text() === label)
  if (!row) throw new Error(`Missing "${label}" row in the salvage calculation`)
  return row
}

function firstTargetEctos(open: VueWrapper): string {
  return open.findAll('.target-row')[0]?.element.children[2]?.textContent?.trim() ?? ''
}

beforeEach(() => {
  requests.length = 0
  luckAnswer = accountLuck
  pricesAnswer = itemPrices
  priceFailure = false
  luckStored = true
  taskState = 'SUCCEEDED'
  admissionCoverage = []
  lastAdmissionCovered = true
  stallAdmission = false
  taskLookupDenied = false
  vi.stubGlobal('fetch', vi.fn((input: string, init?: RequestInit) => {
    requests.push({ method: init?.method ?? 'GET', url: input })
    const url = new URL(input, 'http://test.local')
    if (url.pathname === '/api/items/metadata') return Promise.resolve(jsonResponse(itemMetadata))
    if (url.pathname === '/api/items/prices') {
      return priceFailure
        ? Promise.reject(new Error('prices unavailable'))
        : Promise.resolve(jsonResponse(pricesAnswer))
    }
    if (url.pathname === '/api/account/luck') {
      return luckStored
        ? Promise.resolve(jsonResponse(luckAnswer))
        : Promise.resolve(new Response(
          JSON.stringify({ error: 'CREDENTIAL_REQUIRED', message: 'Luck is not synchronized' }),
          { status: 404, headers: { 'Content-Type': 'application/json' } }
        ))
    }
    if (url.pathname === '/api/sync/account/luck') {
      if (stallAdmission) return new Promise<Response>(() => {})
      lastAdmissionCovered = admissionCoverage.shift() ?? true
      return Promise.resolve(jsonResponse({ taskId: 'luck-1', operation: 'ACCOUNT_SYNC', statusUrl: '/api/sync/tasks/luck-1', coversRequest: lastAdmissionCovered, retryAfterSeconds: lastAdmissionCovered ? undefined : 10 }))
    }
    if (url.pathname === '/api/sync/tasks/luck-1') {
      if (!lastAdmissionCovered || taskLookupDenied) return Promise.resolve(new Response('{}', { status: 404 }))
      if (taskState === 'SUCCEEDED' && lastAdmissionCovered) luckStored = true
      return Promise.resolve(jsonResponse(
        taskState === 'FAILED'
          ? { taskId: 'luck-1', operation: 'ACCOUNT_SYNC', state: 'FAILED', failure: { message: 'upstream down' } }
          : { taskId: 'luck-1', operation: 'ACCOUNT_SYNC', state: 'SUCCEEDED' }
      ))
    }
    throw new Error(`Obsolete or unexpected request: ${input}`)
  }))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('EctoSalvageScreen', () => {
  it('loads metadata for every displayed item, only two TP prices, and current account Luck', async () => {
    await openScreen()

    expect(requests).toHaveLength(3)
    expect(requests.every((request) => request.method === 'GET')).toBe(true)
    const metadataRequest = requests.find(({ url }) => url.startsWith('/api/items/metadata?'))
    const priceRequest = requests.find(({ url }) => url.startsWith('/api/items/prices?'))
    expect(metadataRequest).toBeDefined()
    expect(priceRequest).toBeDefined()
    expect(requests.map(({ url }) => url)).toContain('/api/account/luck')
    expect(new URL(metadataRequest!.url, 'http://test.local').searchParams.get('ids')?.split(',').map(Number))
      .toEqual(METADATA_IDS)
    expect(new URL(priceRequest!.url, 'http://test.local').searchParams.get('ids')?.split(',').map(Number))
      .toEqual([ECTO_ID, DUST_ID])
    expect(requests.some(({ url }) => url.includes('/api/ecto/salvage'))).toBe(false)
  })

  it('passes all seven backend icon URLs to the shared ItemIcon component', async () => {
    const open = await openScreen()
    const icons = open.findAll('[data-test="item-icon"]')

    expect(icons).toHaveLength(METADATA_IDS.length)
    expect(icons.every((icon) => icon.attributes('data-icon-state') === 'image')).toBe(true)
    expect(icons.map((icon) => icon.find('img').attributes('src'))).toEqual([
      44602, 23041, 89409, 67027, 19986, ECTO_ID, DUST_ID
    ].map((id) => `/api/items/${id}/icon/source-${id}.png`))
    expect(open.html()).not.toContain('render.guildwars2.com')
  })

  it('starts with 100 Ectos, Master/Silver-Fed, instant buy, and instant sell', async () => {
    const open = await openScreen()

    expect((open.find('#ecto-count').element as HTMLInputElement).value).toBe('100')
    expect(open.find('.salvage-row.selected').text()).toContain("Master's / Mystic / Silver-Fed")
    expect(open.find('.tool-choice__button--selected').text()).toBe('Silver-Fed')
    expect(open.findAll('.tp-block')[0]?.find('.tp-option.selected').text()).toContain('Instant buy')
    expect(open.findAll('.tp-block')[1]?.find('.tp-option.selected').text()).toContain('Instant sell')
    expect(ungrouped(open.find('.result-details'))).toContain(`${10_457} Luck`)
    expect(open.find('.result-details').text()).toContain('185 Dust')
  })

  it('explains owned-Ecto valuation, statistical yields, and TP quantity limits', async () => {
    const open = await openScreen()

    expect(open.find('.ecto-value-note').text()).toContain("They aren't free to salvage")
    expect(open.find('.ecto-value-note').text()).toContain('Choose Instant Buy or Buy Order')
    expect(open.find('.source-line').text()).toContain('statistical averages')
    expect(open.find('[data-test="tp-price-disclaimer-trigger"]').attributes('aria-label'))
      .toBe('Trading Post price warning')
    expect(open.find('[data-test="tp-price-disclaimer-dialog"]').text())
      .toContain('CHECK IN-GAME PRICE & QUANTITY')
  })

  it('refreshes only Ecto and Dust prices without reloading metadata or account Luck', async () => {
    const open = await openScreen()
    pricesAnswer = {
      prices: [
        { itemId: ECTO_ID, buyUnitCopper: 110, sellUnitCopper: 130 },
        { itemId: DUST_ID, buyUnitCopper: 210, sellUnitCopper: 250 }
      ]
    }

    await buttonIn(open, '.tp-refresh-button', 'Refresh TP prices').trigger('click')
    await flushPromises()

    expect(requests).toHaveLength(4)
    expect(requests.filter(({ url }) => url.startsWith('/api/items/metadata?'))).toHaveLength(1)
    expect(requests.filter(({ url }) => url === '/api/account/luck')).toHaveLength(1)
    expect(requests.filter(({ url }) => url === '/api/items/prices?ids=19721,24277')).toHaveLength(2)
    expect(calculationFigure(open, 'Ecto value consumed').text()).toContain('-1g 30s 0c')
    expect(calculationFigure(open, 'Dust value after TP fees').text()).toContain('+3g 30s 22c')
  })

  it('recalculates quantity and method locally from the Wiki yield assumptions', async () => {
    const open = await openScreen()
    await open.find('#ecto-count').setValue('10')

    expect(ungrouped(open.find('.result-details'))).toContain(`${1_046} Luck`)
    expect(open.find('.result-details').text()).toContain('19 Dust')
    expect(open.find('.result-details').text()).toContain('6s 0c') // 10 Silver-Fed uses
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-13s 45c')

    await buttonIn(open, '.salvage-row', 'Basic / Copper-Fed').trigger('click')
    expect(open.find('.tool-choice__button--selected').text()).toBe('Copper-Fed')
    expect(ungrouped(open.find('.result-details'))).toContain(`${1_032} Luck`)
    expect(open.find('.result-details').text()).toContain('16 Dust')
    expect(open.find('.result-details').text()).toContain('30c')
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-15s 40c')
    expect(requests).toHaveLength(3)
  })

  it('charges the selected exact tool in coin rather than treating a method as one cost', async () => {
    const open = await openScreen()
    expect(open.find('.result-details').text()).toContain('60s 0c') // Silver-Fed, 100 uses
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-1g 34s 50c')

    await buttonIn(open, '.tool-choice__button', 'Mystic').trigger('click')
    expect(open.find('.result-details').text()).toContain('10s 50c') // rounded 100 x 10.496c
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-1g 84s 0c')
    expect(open.find('.tool-cost-note').text()).toContain('Mystic Forge Stone')
    expect(requests).toHaveLength(3)
  })

  it('keeps Black Lion Gem cost separate from the effective coin cost', async () => {
    const open = await openScreen()
    await buttonIn(open, '.salvage-row', 'Black Lion').trigger('click')

    expect(ungrouped(open.find('.result-details'))).toContain(`${1_200} Gems`)
    // A gem-priced tool renames the coin total and files the gems in their own row.
    expect(calculationFigure(open, 'Effective coin result').text()).toContain('-2g 26s 80c')
    expect(calculationFigure(open, 'Effective coin result').text()).not.toContain('Gems')
    expect(ungrouped(calculationFigure(open, 'Additional cost'))).toContain(`${1_200} Gems`)
    expect(open.find('.tool-cost-note').text()).toContain('not converted to gold')
    expect(open.find('.target-row .target-cost small').text()).toContain('Gems')
    expect(requests).toHaveLength(3)
  })

  it('applies the 15% Dust sale fee and recalculates both TP modes locally', async () => {
    const open = await openScreen()
    expect(calculationFigure(open, 'Dust value after TP fees').text()).toContain('3g 14s 50c') // 185 x 200 x 0.85
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-1g 34s 50c')

    await buttonIn(open, '.tp-block:nth-child(2) .tp-option', 'Listing sell').trigger('click')
    expect(calculationFigure(open, 'Dust value after TP fees').text()).toContain('3g 77s 40c') // 185 x 240 x 0.85
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-1g 97s 40c')

    await buttonIn(open, '.tp-block:first-child .tp-option', 'Buy order').trigger('click')
    expect(calculationFigure(open, 'Ecto value consumed').text()).toContain('-1g 0s 0c')
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-2g 17s 40c')
    expect(requests).toHaveLength(3)
  })

  it('prices Luck targets using the selected yield, exact tool, and TP modes', async () => {
    const open = await openScreen()
    expect(open.find('.target-header').text()).toContain('Ecto value')
    expect(open.find('.target-header').text()).toContain('Effective cost')
    expect(firstTargetEctos(open)).toBe('4') // ceil(416 / 104.57)
    expect(open.findAll('.target-row')[0]?.element.children[3]?.textContent?.trim()).toBe('4s 80c')
    expect(open.find('.target-row .target-cost strong').text()).toBe('-5s 38c')

    await buttonIn(open, '.salvage-row', 'Basic / Copper-Fed').trigger('click')
    expect(firstTargetEctos(open)).toBe('5') // ceil(416 / 103.17)
    expect(open.find('.target-row .target-cost strong').text()).toBe('-7s 70c')
    await buttonIn(open, '.tool-choice__button', 'Basic').trigger('click')
    expect(open.find('.target-row .target-cost strong').text()).toBe('-7s 67c')
    await buttonIn(open, '.tp-block:first-child .tp-option', 'Buy order').trigger('click')
    expect(open.find('.target-row .target-cost strong').text()).toBe('-8s 67c')
    expect(requests).toHaveLength(3)
  })

  it('uses the current and next cumulative Luck thresholds for level progress', async () => {
    const open = await openScreen()
    const bar = open.find('.luck-progress__bar')
    const expectedPercent = ((14_134 - 13_790) / (14_550 - 13_790)) * 100
    expect(parseFloat((bar.element as HTMLElement).style.width)).toBeCloseTo(expectedPercent, 8)
  })

  it('files the account Luck figures under their own hook, apart from the result hook', async () => {
    const open = await openScreen()
    const luck = open.find('[data-test="ecto-account-luck"]')
    const result = open.find('[data-test="ecto-result"]')

    expect(result.exists()).toBe(true)
    // The supplied `accountLuck.consumedLuck`, by its digits rather than by this runner's locale.
    expect(ungrouped(luck)).toContain(String(14_134))
    expect(luck.element).not.toBe(result.element)
    expect(requests).toHaveLength(3)
  })

  it('withholds the result hook and warns when the Trading Post quotes could not be read', async () => {
    priceFailure = true
    const open = await openScreen()

    expect(open.find('[role="alert"]').text()).toContain('Trading Post prices could not be read.')
    // The screen root and the Luck section are both on screen, so neither of them can stand in for
    // a calculated result the missing quotes made impossible.
    expect(open.find('[data-test="ecto-screen"]').exists()).toBe(true)
    expect(open.find('[data-test="ecto-account-luck"]').exists()).toBe(true)
    expect(open.find('[data-test="ecto-result"]').exists()).toBe(false)
    expect(open.find('.result-details').text()).toContain('185 Dust')
  })

  it('renders a full progress bar at the 300% Luck-derived Magic Find cap', async () => {
    luckAnswer = cappedAccountLuck
    const open = await openScreen()

    expect(open.find('.account-summary').text()).toContain('300%')
    expect(open.find('.account-summary').text()).toContain('Maximum')
    expect((open.find('.luck-progress__bar').element as HTMLElement).style.width).toBe('100%')
  })

  it('recovers never-published Luck with one Luck-only refresh, then shows the stored Luck', async () => {
    luckStored = false
    const open = await openScreen()

    const posts = requests.filter(({ method }) => method === 'POST').map(({ url }) => url)
    expect(posts).toEqual(['/api/sync/account/luck'])
    expect(requests.map(({ url }) => url)).not.toContain('/api/sync/account/crafting-profit')
    expect(open.find('[data-test="ecto-luck-missing"]').exists()).toBe(false)
    expect(ungrouped(open.find('[data-test="ecto-account-luck"]'))).toContain(String(14_134))
  })

  it('reports failed Luck recovery as unknown (never zero) and retries on request', async () => {
    luckStored = false
    taskState = 'FAILED'
    const open = await openScreen()

    expect(open.find('[data-test="ecto-account-luck"]').exists()).toBe(false)
    expect(open.find('[data-test="ecto-luck-missing"]').text()).toContain('upstream down')
    expect(open.find('[data-test="ecto-luck-missing"]').text()).toContain('unknown')
    expect(requests.filter(({ method }) => method === 'POST')).toHaveLength(1)

    taskState = 'SUCCEEDED'
    await open.find('[data-test="ecto-luck-refresh"]').trigger('click')
    await flushPromises()

    expect(requests.filter(({ method }) => method === 'POST')).toHaveLength(2)
    expect(open.find('[data-test="ecto-luck-missing"]').exists()).toBe(false)
    expect(open.find('[data-test="ecto-account-luck"]').exists()).toBe(true)
  })

  it('retries admission after the advertised delay without observing an inaccessible Profit task', async () => {
    vi.useFakeTimers()
    luckStored = false
    admissionCoverage = [false, true]
    const open = await openScreen()
    await vi.advanceTimersByTimeAsync(9_999)
    expect(requests.filter(({ method }) => method === 'POST')).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(requests.filter(({ method }) => method === 'POST')).toHaveLength(2)
    expect(requests.filter(({ url }) => url.startsWith('/api/sync/tasks/'))).toHaveLength(1)
    expect(open.find('[data-test="ecto-account-luck"]').exists()).toBe(true)
  })

  it('stops repeated uncovered admissions and offers retry without inventing Luck', async () => {
    vi.useFakeTimers()
    luckStored = false
    admissionCoverage = [false, false, false, false, false]
    const open = await openScreen()
    await vi.advanceTimersByTimeAsync(40_000)
    await flushPromises()
    expect(requests.filter(({ method }) => method === 'POST')).toHaveLength(4)
    expect(requests.some(({ url }) => url.startsWith('/api/sync/tasks/'))).toBe(false)
    expect(open.find('[data-test="ecto-account-luck"]').exists()).toBe(false)
    expect(open.find('[data-test="ecto-luck-refresh-error"]').text()).toContain('Another refresh kept running')
  })

  it('ends the visible recovery budget even when admission never answers', async () => {
    vi.useFakeTimers()
    luckStored = false
    stallAdmission = true
    const open = await openScreen()
    expect(open.find('[data-test="ecto-luck-refresh"]').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()
    expect(open.find('[data-test="ecto-luck-refresh-error"]').text()).toContain('may still be running')
    expect(open.find('[data-test="ecto-luck-refresh"]').attributes('disabled')).toBeUndefined()
    expect(requests.filter(({ method }) => method === 'POST')).toHaveLength(1)
  })

  it('does not start Luck work when an initial missing-data read resolves after leaving', async () => {
    const base = vi.mocked(fetch).getMockImplementation()!
    let release!: (response: Response) => void
    const held = new Promise<Response>((resolve) => { release = resolve })
    vi.stubGlobal('fetch', vi.fn((input: string, init?: RequestInit) =>
      input === '/api/account/luck' ? held : base(input, init)))
    const open = await openScreen()
    open.unmount()
    wrapper = null
    release(new Response(JSON.stringify({ error: 'CREDENTIAL_REQUIRED', message: 'missing' }),
      { status: 404, headers: { 'Content-Type': 'application/json' } }))
    await flushPromises()
    expect(requests.filter(({ method }) => method === 'POST')).toHaveLength(0)
  })

  it('does not continue polling when a timed-out admission answers late', async () => {
    vi.useFakeTimers()
    luckStored = false
    const base = vi.mocked(fetch).getMockImplementation()!
    let release!: (response: Response) => void
    const held = new Promise<Response>((resolve) => { release = resolve })
    vi.stubGlobal('fetch', vi.fn((input: string, init?: RequestInit) =>
      input === '/api/sync/account/luck' ? held : base(input, init)))
    const open = await openScreen()
    await vi.advanceTimersByTimeAsync(60_000)
    release(jsonResponse({ taskId: 'luck-1', statusUrl: '/api/sync/tasks/luck-1' }))
    await flushPromises()
    expect(requests.some(({ url }) => url.startsWith('/api/sync/tasks/'))).toBe(false)
    expect(open.find('[data-test="ecto-luck-refresh-error"]').text()).toContain('may still be running')
  })

  it('reads authorized Luck published by a covered full task whose status needs broader grants', async () => {
    vi.useFakeTimers()
    luckStored = false
    taskLookupDenied = true
    const open = await openScreen()
    expect(open.find('[data-test="ecto-account-luck"]').exists()).toBe(false)
    expect(requests.filter(({ method }) => method === 'POST')).toHaveLength(1)
    luckStored = true // The other session's full refresh publishes this source.
    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
    expect(open.find('[data-test="ecto-account-luck"]').exists()).toBe(true)
    expect(requests.filter(({ url }) => url.startsWith('/api/sync/tasks/'))).toHaveLength(1)
    expect(requests.filter(({ method }) => method === 'POST')).toHaveLength(1)
  })
})
