import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BankContents } from '@/api/types'
import { deferred, bankWithEmptySlots, materialStorage } from '@/account/__tests__/accountFixtures'
import {
  discoveryResolutionResponse,
  echoedDiscoveryResponse,
  profitResponse,
  selectorOptions
} from '@/crafting/__tests__/fixtures'
import { accountLuck, itemMetadata, itemPrices, METADATA_IDS } from '@/ecto/__tests__/currentEctoFixtures'

/**
 * The application shell: navigation between the areas, what each navigation costs at the network
 * boundary, and what survives moving between them (STORY-WEB-004).
 *
 * `fetch` is stubbed, so every assertion here is about what the browser would request and when. The
 * real API clients are used on purpose — this is the check that opening an area calls that area's own
 * route, that no navigation submits a calculation or a synchronization operation, and that an
 * unfinished synchronization keeps being tracked without a second trigger or a second polling loop.
 */
interface RecordedRequest {
  readonly method: string
  readonly path: string
}

const requested: RecordedRequest[] = []
let bankAnswer: Promise<BankContents> = Promise.resolve(bankWithEmptySlots)
let wrapper: VueWrapper | null = null

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

const SYNC_TASK_ID = 'task-account-1'

async function answer(path: string, init?: RequestInit): Promise<Response> {
  if (path === '/api/crafting/selector-options') return jsonResponse(selectorOptions)
  if (path === '/api/crafting/profit') return jsonResponse(profitResponse())
  // The two Discovery routes answer from the request body, the way the contract does: the screen
  // reads its control state and its detail's identity out of that echo.
  if (path === '/api/crafting/discovery') {
    return jsonResponse(echoedDiscoveryResponse(JSON.parse(String(init?.body ?? '{}'))))
  }
  if (path === '/api/crafting/discovery/resolution') {
    return jsonResponse(discoveryResolutionResponse(JSON.parse(String(init?.body ?? '{}'))))
  }
  if (path.startsWith('/api/items/metadata?')) return jsonResponse(itemMetadata)
  if (path.startsWith('/api/items/prices?')) return jsonResponse(itemPrices)
  if (path === '/api/account/luck') return jsonResponse(accountLuck)
  if (path === '/api/account/bank') return jsonResponse(await bankAnswer)
  if (path === '/api/account/materials') return jsonResponse(materialStorage)
  if (path === '/api/sync/account') {
    return jsonResponse(
      {
        taskId: SYNC_TASK_ID,
        operation: 'ACCOUNT_SYNC',
        statusUrl: `/api/sync/tasks/${SYNC_TASK_ID}`
      },
      202
    )
  }
  if (path === `/api/sync/tasks/${SYNC_TASK_ID}`) {
    return jsonResponse({
      taskId: SYNC_TASK_ID,
      operation: 'ACCOUNT_SYNC',
      state: 'RUNNING',
      submittedAt: '2026-09-25T10:00:00Z',
      startedAt: '2026-09-25T10:00:01Z',
      finishedAt: null,
      failure: null
    })
  }
  throw new Error(`Unexpected request: ${path}`)
}

/** Imported late, so each test sees the shell with the location hash it set up. */
async function openApp(): Promise<VueWrapper> {
  const App = (await import('../App.vue')).default
  wrapper = mount(App, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

async function navigateTo(open: VueWrapper, destination: string): Promise<void> {
  await open.find(`[data-test="nav-${destination}"]`).trigger('click')
  await flushPromises()
}

function pathsOf(prefix: string): string[] {
  return requested.filter((request) => request.path.startsWith(prefix)).map(({ path }) => path)
}

function currentDestination(open: VueWrapper): string | undefined {
  return open.find('[aria-current="page"]').attributes('data-test')
}

beforeEach(() => {
  requested.length = 0
  bankAnswer = Promise.resolve(bankWithEmptySlots)
  window.history.replaceState(null, '', '/')
  vi.stubGlobal(
    'fetch',
    vi.fn((input: string, init?: RequestInit) => {
      requested.push({ method: init?.method ?? 'GET', path: input })
      return answer(input, init)
    })
  )
})

afterEach(() => {
  // Unmounted so no tracked task keeps a timer alive past the test that started it.
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
})

describe('App shell', () => {
  it('opensOnCraftingProfitWithThatDestinationMarkedAndNothingElseRequested', async () => {
    const open = await openApp()

    expect(open.find('[data-test="profit-table"]').exists()).toBe(true)
    expect(open.find('[data-test="page-heading"]').text()).toBe('Crafting Profit')
    expect(currentDestination(open)).toBe('nav-crafting')
    expect(document.title).toBe('Crafting Profit · GW2 Crafting Tool')
    expect(pathsOf('/api/account')).toEqual([])
    expect(pathsOf('/api/sync')).toEqual([])
  })

  it('offersOnlyTheImplementedDestinationsAsRealLinks', async () => {
    const open = await openApp()

    const links = open.findAll('[data-test="screen-nav"] a')
    expect(links.map((link) => link.attributes('href'))).toEqual([
      '#/crafting',
      '#/discovery',
      '#/ecto',
      '#/synchronization',
      '#/bank',
      '#/materials'
    ])
    // Real links, so they are reachable and operable by keyboard without any handler of ours.
    expect(links.every((link) => link.element.tagName === 'A')).toBe(true)
    expect(open.find('.skip-link').attributes('href')).toBe('#main-content')
  })

  it('opensEachDestinationWithItsOwnUrlTitleAndFocusedHeading', async () => {
    const open = await openApp()

    await navigateTo(open, 'synchronization')

    expect(window.location.hash).toBe('#/synchronization')
    expect(document.title).toBe('Synchronization · GW2 Crafting Tool')
    expect(currentDestination(open)).toBe('nav-synchronization')
    const heading = open.find('[data-test="page-heading"]')
    expect(heading.text()).toBe('Synchronization')
    expect(document.activeElement).toBe(heading.element)
  })

  it('opensCraftingDiscoveryAsItsOwnAddressableDestinationAndCalculatesThere', async () => {
    const open = await openApp()

    await navigateTo(open, 'discovery')

    // A real URL of its own, so Back, Forward, a bookmark and a reload all work (STORY-WEB-012).
    expect(window.location.hash).toBe('#/discovery')
    expect(document.title).toBe('Crafting Discovery · GW2 Crafting Tool')
    expect(currentDestination(open)).toBe('nav-discovery')
    const heading = open.find('[data-test="page-heading"]')
    expect(heading.text()).toBe('Crafting Discovery')
    expect(document.activeElement).toBe(heading.element)

    // Opening it posts its own calculation once, on its own route, and nothing else.
    expect(pathsOf('/api/crafting/discovery')).toEqual(['/api/crafting/discovery'])
    expect(open.find('[data-test="discovery-table"]').exists()).toBe(true)
    expect(open.find('[data-test="profit-table"]').exists()).toBe(false)
    expect(pathsOf('/api/account')).toEqual([])
    expect(pathsOf('/api/sync')).toEqual([])
  })

  it('opensEctoplasmSalvageAsItsOwnAddressableDestinationAndLoadsItsThreeInputs', async () => {
    const open = await openApp()

    await navigateTo(open, 'ecto')

    // A real URL of its own, so Back, Forward, a bookmark and a reload all work (STORY-WEB-013).
    expect(window.location.hash).toBe('#/ecto')
    expect(document.title).toBe('Ecto Salvage · GW2 Crafting Tool')
    expect(currentDestination(open)).toBe('nav-ecto')
    const heading = open.find('[data-test="page-heading"]')
    expect(heading.text()).toBe('Ecto Salvage')
    expect(document.activeElement).toBe(heading.element)
    // The destination is named the same way in all three places (STORY-WEB-022): the navigation link,
    // the page's own heading and the document title, so no one name can be renamed on its own.
    expect(open.find('[data-test="nav-ecto"]').text()).toBe(heading.text())

    expect(pathsOf('/api/ecto')).toEqual([])
    expect(pathsOf('/api/items/metadata')).toHaveLength(1)
    expect(pathsOf('/api/items/prices')).toEqual(['/api/items/prices?ids=19721,24277'])
    expect(new URL(pathsOf('/api/items/metadata')[0]!, 'http://test.local').searchParams.get('ids')?.split(',').map(Number))
      .toEqual(METADATA_IDS)
    expect(pathsOf('/api/account')).toEqual(['/api/account/luck'])
    expect(open.find('[data-test="ecto-screen"]').exists()).toBe(true)
    expect(open.find('.salvage-calculation').exists()).toBe(true)
    expect(pathsOf('/api/sync')).toEqual([])
  })

  it('reloadsEctoplasmInputsOnReturnWithoutCallingTheRemovedCalculationRoute', async () => {
    const open = await openApp()

    await navigateTo(open, 'ecto')
    await navigateTo(open, 'bank')
    await navigateTo(open, 'ecto')

    expect(pathsOf('/api/ecto')).toEqual([])
    expect(pathsOf('/api/items/metadata')).toHaveLength(2)
    expect(pathsOf('/api/items/prices')).toHaveLength(2)
    expect(pathsOf('/api/account/luck')).toHaveLength(2)
  })

  it('keepsTheTwoCraftingScreensSeparateAndPostsNoSecondCalculationOnReturn', async () => {
    const open = await openApp()
    const profitCalculations = pathsOf('/api/crafting/profit').length

    await navigateTo(open, 'discovery')
    await open.find('[data-test="discovery-search"]').setValue('Iron')
    await open.findAll('[data-test="discovery-select-row"]')[0]?.trigger('click')
    await flushPromises()
    const selectedRecipe = open.find('[data-test="discovery-detail-name"]').text()
    expect(pathsOf('/api/crafting/discovery/resolution')).toHaveLength(1)

    await navigateTo(open, 'crafting')

    // Opening Discovery did not re-post Profit's calculation, and returning to Profit does not
    // re-post it either: the two screens are independent kept-alive boundaries.
    expect(pathsOf('/api/crafting/profit')).toHaveLength(profitCalculations)

    await navigateTo(open, 'discovery')

    // The Discovery selection survived the trip with no second table calculation; only the detail is
    // asked for again, freshly, as 13.4 requires after leaving the view.
    expect(pathsOf('/api/crafting/discovery')).toEqual([
      '/api/crafting/discovery',
      '/api/crafting/discovery/resolution',
      '/api/crafting/discovery/resolution'
    ])
    expect((open.find('[data-test="discovery-search"]').element as HTMLInputElement).value).toBe('Iron')
    expect(open.find('[data-test="discovery-detail-name"]').text()).toBe(selectedRecipe)
  })

  it('opensCraftingProfitWhenTheUrlNamesNoKnownDestination', async () => {
    window.history.replaceState(null, '', '#/not-a-screen')

    const open = await openApp()

    expect(open.find('[data-test="page-heading"]').text()).toBe('Crafting Profit')
    // The address bar is corrected rather than left claiming a destination that does not exist.
    expect(window.location.hash).toBe('#/crafting')
  })

  it('followsTheBrowsersOwnHashNavigationSoBackAndForwardWork', async () => {
    const open = await openApp()
    await navigateTo(open, 'bank')

    window.history.replaceState(null, '', '#/materials')
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    await flushPromises()

    expect(open.find('[data-test="materials-screen"]').exists()).toBe(true)
    expect(currentDestination(open)).toBe('nav-materials')
  })

  it('loadsTheBankRouteWhenTheBankScreenIsOpened', async () => {
    const open = await openApp()

    await navigateTo(open, 'bank')

    expect(pathsOf('/api/account')).toEqual(['/api/account/bank'])
    expect(open.find('[data-test="bank-screen"]').exists()).toBe(true)
    expect(open.find('[data-test="profit-table"]').exists()).toBe(false)
  })

  it('loadsTheMaterialsRouteWhenTheMaterialsScreenIsOpenedAndTheBankRouteAgainOnReturn', async () => {
    const open = await openApp()

    await navigateTo(open, 'bank')
    await navigateTo(open, 'materials')
    await navigateTo(open, 'bank')

    expect(pathsOf('/api/account')).toEqual([
      '/api/account/bank',
      '/api/account/materials',
      '/api/account/bank'
    ])
    expect(open.find('[data-test="materials-screen"]').exists()).toBe(false)
    expect(open.findAll('[data-test="bank-slot"]')).toHaveLength(bankWithEmptySlots.slots.length)
  })

  it('triggersNoSynchronizationAndCallsNoOtherHostWhileNavigating', async () => {
    const open = await openApp()

    await navigateTo(open, 'bank')
    await navigateTo(open, 'synchronization')
    await navigateTo(open, 'materials')
    await navigateTo(open, 'crafting')

    // Opening the synchronization area is not a trigger, and returning from it is not a second one.
    expect(pathsOf('/api/sync')).toEqual([])
    expect(pathsOf('/api/prices')).toEqual([])
    expect(requested.every((request) => request.path.startsWith('/api/'))).toBe(true)
  })

  it('keepsTheCraftingSelectionAndPostsNoSecondCalculationWhenReturningToIt', async () => {
    const open = await openApp()
    await open.find('[data-test="search"]').setValue('Iron')
    // The display controls are part of that selection: they too must survive leaving and returning.
    await open.find('[data-test="filter-zero-craftable"]').setValue(false)
    // The maximum is applied when the entry is committed, not on every keystroke.
    const maximum = open.find('[data-test="max-displayed"]')
    ;(maximum.element as HTMLInputElement).value = '25'
    await maximum.trigger('change')
    await flushPromises()
    const rowsBefore = open.findAll('[data-test="profit-row"]').length
    const calculationsBefore = pathsOf('/api/crafting/profit').length

    await navigateTo(open, 'bank')
    await navigateTo(open, 'crafting')

    expect(pathsOf('/api/crafting/profit')).toHaveLength(calculationsBefore)
    expect((open.find('[data-test="search"]').element as HTMLInputElement).value).toBe('Iron')
    expect((open.find('[data-test="filter-zero-craftable"]').element as HTMLInputElement).checked).toBe(false)
    expect((open.find('[data-test="max-displayed"]').element as HTMLInputElement).value).toBe('25')
    expect(open.findAll('[data-test="profit-row"]')).toHaveLength(rowsBefore)
  })

  it('keepsAnUnfinishedSynchronizationTrackedWhileAnotherAreaIsOpen', async () => {
    const open = await openApp()
    await navigateTo(open, 'synchronization')
    await open.find('[data-test="sync-trigger-ACCOUNT_SYNC"]').trigger('click')
    await flushPromises()

    expect(open.find('[data-test="sync-state-ACCOUNT_SYNC"]').text()).toBe('Running')
    const triggersWhileOnThePage = pathsOf('/api/sync/account').length
    const lookupsWhileOnThePage = pathsOf(`/api/sync/tasks/`).length

    await navigateTo(open, 'materials')

    // The activity indication is on the link to that area; it is not a control and starts nothing.
    expect(open.find('[data-test="nav-sync-activity"]').text()).toBe('1 task running')
    expect(pathsOf('/api/sync/account')).toHaveLength(triggersWhileOnThePage)

    await navigateTo(open, 'synchronization')

    expect(open.find('[data-test="sync-state-ACCOUNT_SYNC"]').text()).toBe('Running')
    expect(open.find('[data-test="sync-task-ACCOUNT_SYNC"]').text()).toContain(SYNC_TASK_ID)
    // Neither the trigger nor a second polling loop followed from leaving and coming back.
    expect(pathsOf('/api/sync/account')).toHaveLength(triggersWhileOnThePage)
    expect(pathsOf('/api/sync/tasks/')).toHaveLength(lookupsWhileOnThePage)
  })

  it('doesNotLetABankAnswerThatArrivesAfterNavigationReachTheMaterialsScreen', async () => {
    const pendingBank = deferred<BankContents>()
    bankAnswer = pendingBank.promise
    const open = await openApp()

    await navigateTo(open, 'bank')
    expect(open.find('[data-test="bank-loading"]').exists()).toBe(true)

    await navigateTo(open, 'materials')
    pendingBank.resolve(bankWithEmptySlots)
    await flushPromises()

    expect(open.find('[data-test="materials-screen"]').exists()).toBe(true)
    expect(open.findAll('[data-test="material-category"]')).toHaveLength(
      materialStorage.categories.length
    )
    expect(open.find('[data-test="bank-screen"]').exists()).toBe(false)
    expect(open.findAll('[data-test="bank-slot"]')).toHaveLength(0)
  })
})
