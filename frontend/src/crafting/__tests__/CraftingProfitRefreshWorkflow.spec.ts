import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '@/api/http'
import type { SyncApi } from '@/api/syncApi'
import type {
  CalculationFreshness,
  CraftingProfitResponse,
  CraftingRow,
  SyncTaskStatus
} from '@/api/types'
import CraftingProfitScreen from '../CraftingProfitScreen.vue'
import { RECOVERY_BUDGET_MS, timerAlarm, type RecoveryAlarm } from '../useCraftingProfit'
import {
  ACCOUNT_SOURCES,
  DEFAULT_SETTINGS,
  FakeCraftingApi,
  accountWork,
  finalFreshness,
  pendingProfitResponse,
  profitResponse,
  profitableRow,
  provisionalFreshness,
  resolutionResponse,
  tradingPostWork,
  uncoveredPrices
} from './fixtures'

/**
 * The provisional-result lifecycle of UD-015, driven through the real Crafting Profit screen with a
 * controlled transport and a controlled clock (STORY-WEB-034).
 *
 * Every response here is supplied by the test. No clock, timer, network, database or display is
 * involved: the screen's `refreshClock` prop is this file's {@link controlledClock}, whose `sleep`
 * parks the recovery cycle until the test releases it, so after each `flushPromises()` the page is
 * in a settled state and an assertion observes exactly one step of the cycle.
 *
 * The rows below carry totals that are deliberately *not* the product of their parts, so a page
 * that worked any money value out itself would disagree with the fixture rather than agree by
 * accident (`TEST_STRATEGY.md` 12).
 */

/** 7 x 100 is 700, not 1 234; 7 x 380 is 2 660, not 9 876. */
const cachedRow: CraftingRow = {
  ...profitableRow,
  craftableCount: 7,
  profitCopper: 100,
  revenueCopper: 380,
  totalProfitCopper: 1_234,
  totalSellValueCopper: 9_876,
  totalMatsSellValueCopper: 4_000,
  buyCostCopper: 2_500
}

/** The same recipe after the refresh landed: a different generation's own supplied numbers. */
const refreshedRow: CraftingRow = {
  ...cachedRow,
  craftableCount: 9,
  profitCopper: 150,
  totalProfitCopper: 4_321,
  totalSellValueCopper: 5_555
}

/** The row a detail answer carries when it was computed from inputs newer than the table's. */
const recalculatedDetailRow: CraftingRow = {
  ...cachedRow,
  craftableCount: 8,
  totalProfitCopper: 7_777,
  totalSellValueCopper: 6_666
}

const CACHED_PROFIT = '+12s 34c'
const REFRESHED_PROFIT = '+43s 21c'
const RECALCULATED_DETAIL_PROFIT = '+77s 77c'

/**
 * A clock, a waiting facility and a timer the test owns.
 *
 * `sleep` resolves only when {@link ControlledClock.tick} is called, and each release advances the
 * clock by `advancePerSleepMs`. The advance is deliberately larger than the poll interval the
 * implementation asks for: the budget is measured against this clock, so a handful of ticks reaches
 * the five minutes without the test asserting anything about how long a real wait would be.
 *
 * `advance` moves the clock *without* releasing anything the cycle is waiting for, which is how the
 * tests below let the five minutes pass while a task-status read or a recalculation is still
 * unanswered. Only a due `alarm` can act then — and an alarm is the one thing the implementation
 * has that does not need a request to answer first.
 */
interface ControlledClock {
  now(): number
  sleep(milliseconds: number): Promise<void>
  alarm(milliseconds: number): RecoveryAlarm
  /** Releases the waits currently parked, one round per call, and lets the page settle. */
  tick(rounds?: number): Promise<void>
  /** Moves the clock on, fires whatever alarm that made due, and lets the page settle. */
  advance(milliseconds: number): Promise<void>
  waiting(): number
  /** Alarms neither fired nor cancelled: a cycle that ended leaves none behind. */
  pendingAlarms(): number
  elapsedMs(): number
  requestedWaits: number[]
}

interface PendingAlarm {
  dueAt: number
  fire(): void
}

function controlledClock(advancePerSleepMs = 30_000): ControlledClock {
  const origin = 1_700_000_000_000
  let current = origin
  let parked: Array<() => void> = []
  let alarms: PendingAlarm[] = []
  const requestedWaits: number[] = []

  function fireDueAlarms(): void {
    const due = alarms.filter((alarm) => alarm.dueAt <= current)
    alarms = alarms.filter((alarm) => alarm.dueAt > current)
    for (const alarm of due) alarm.fire()
  }

  return {
    now: () => current,
    sleep(milliseconds: number): Promise<void> {
      requestedWaits.push(milliseconds)
      return new Promise<void>((resolve) => parked.push(resolve))
    },
    alarm(milliseconds: number): RecoveryAlarm {
      let fire!: () => void
      const expired = new Promise<void>((resolve) => {
        fire = resolve
      })
      const pending: PendingAlarm = { dueAt: current + milliseconds, fire }
      alarms.push(pending)
      return {
        expired,
        cancel: () => {
          alarms = alarms.filter((alarm) => alarm !== pending)
        }
      }
    },
    async tick(rounds = 1): Promise<void> {
      for (let round = 0; round < rounds; round++) {
        const releasing = parked
        parked = []
        if (releasing.length === 0) return
        current += advancePerSleepMs
        fireDueAlarms()
        for (const resolve of releasing) resolve()
        await flushPromises()
      }
    },
    async advance(milliseconds: number): Promise<void> {
      current += milliseconds
      fireDueAlarms()
      await flushPromises()
    },
    waiting: () => parked.length,
    pendingAlarms: () => alarms.length,
    elapsedMs: () => current - origin,
    requestedWaits
  }
}

/**
 * The synchronization API, recording what the page asked of it.
 *
 * `writes` stays empty in every test below: the page never starts a refresh itself. The backend
 * admits refresh work as part of answering the calculation, so a second account writer cannot
 * appear from this screen (AC4, AC5).
 */
interface RecordingSyncApi extends SyncApi {
  readonly statusReads: string[]
  readonly writes: string[]
}

function recordingSyncApi(
  status: (statusUrl: string, callIndex: number) => Promise<SyncTaskStatus> | SyncTaskStatus
): RecordingSyncApi {
  const statusReads: string[] = []
  const writes: string[] = []
  const refuse = (name: string) => () => {
    writes.push(name)
    return Promise.reject(new Error(`${name} must not be called by the Crafting Profit page.`))
  }

  return {
    statusReads,
    writes,
    startAccountSync: refuse('startAccountSync'),
    startProfitDataRefresh: refuse('startProfitDataRefresh'),
    startGlobalSync: refuse('startGlobalSync'),
    startPriceRefresh: refuse('startPriceRefresh'),
    async readTaskStatus(statusUrl: string): Promise<SyncTaskStatus> {
      const callIndex = statusReads.length
      statusReads.push(statusUrl)
      return status(statusUrl, callIndex)
    }
  }
}

function taskState(taskId: string, state: string): SyncTaskStatus {
  return {
    taskId,
    operation: 'ACCOUNT_SYNC',
    state,
    submittedAt: '2026-10-13T09:00:00Z',
    startedAt: '2026-10-13T09:00:01Z',
    finishedAt: state === 'RUNNING' ? null : '2026-10-13T09:00:09Z',
    failure: null
  }
}

/** Answers every status read with the state the test named for that task id. */
function statesByTask(states: Record<string, string>) {
  return (statusUrl: string): SyncTaskStatus => {
    const taskId = statusUrl.split('/').at(-1) ?? 'task'
    return taskState(taskId, states[taskId] ?? 'RUNNING')
  }
}

interface Screen {
  wrapper: VueWrapper
  api: FakeCraftingApi
  sync: RecordingSyncApi
  clock: ControlledClock
}

async function openScreen(
  api: FakeCraftingApi,
  sync: RecordingSyncApi,
  clock: ControlledClock = controlledClock()
): Promise<Screen> {
  const wrapper = mount(CraftingProfitScreen, {
    props: { api, refreshApi: sync, refreshClock: clock }
  })
  await flushPromises()
  return { wrapper, api, sync, clock }
}

function rowNames(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="profit-row"] .recipe-name').map((element) => element.text())
}

function tableProfits(wrapper: VueWrapper): string[] {
  return wrapper.findAll('[data-test="total-profit"]').map((element) => element.text())
}

function text(wrapper: VueWrapper, test: string): string {
  const found = wrapper.find(`[data-test="${test}"]`)
  return found.exists() ? found.text() : ''
}

function exists(wrapper: VueWrapper, test: string): boolean {
  return wrapper.find(`[data-test="${test}"]`).exists()
}

async function selectRecipe(wrapper: VueWrapper, recipeName: string): Promise<void> {
  const index = rowNames(wrapper).indexOf(recipeName)
  if (index < 0) throw new Error(`No row for "${recipeName}" — rows: ${rowNames(wrapper).join(', ')}`)
  await wrapper.findAll('[data-test="select-row"]')[index]?.trigger('click')
  await flushPromises()
}

/**
 * A request that answers only when the test says so. Used to hold a task-status read or a
 * recalculation pending across the recovery deadline, which is the state no check between awaits
 * can ever observe.
 */
function deferred<T>(): { promise: Promise<T>; resolve(value: T): void } {
  let settle!: (value: T) => void
  const promise = new Promise<T>((resolve) => {
    settle = resolve
  })
  return { promise, resolve: (value: T) => settle(value) }
}

/** Serves one scripted response per calculation request, repeating the last one afterwards. */
function scriptedProfit(api: FakeCraftingApi, script: CraftingProfitResponse[]): void {
  api.profitHandler = (_request, callIndex) =>
    Promise.resolve(script[Math.min(callIndex, script.length - 1)] as CraftingProfitResponse)
}

describe('Crafting Profit provisional refresh workflow', () => {
  describe('a provisional result is usable immediately (AC1)', () => {
    it('showsTheSuppliedCachedRowsWithTheStaleIndicatorBeforeAnyRefreshFinishes', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()),
        profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      // The advertised task never leaves RUNNING, so the cycle is still mid-poll here.
      const { wrapper, sync, clock } = await openScreen(
        api,
        recordingSyncApi(statesByTask({}))
      )

      expect(rowNames(wrapper)).toEqual([cachedRow.outputName])
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'loading')).toBe(false)
      expect(exists(wrapper, 'pending')).toBe(false)

      expect(exists(wrapper, 'stale-data-indicator')).toBe(true)
      expect(text(wrapper, 'stale-data-chip')).toContain('Stale data – refreshing…')
      expect(text(wrapper, 'stale-data-explanation')).toContain(
        'calculated from the data that last synchronized successfully'
      )
      expect(text(wrapper, 'stale-data-explanation')).toContain('replaced automatically')
      expect(text(wrapper, 'stale-data-outstanding')).toContain('account data')
      expect(exists(wrapper, 'refresh-failed')).toBe(false)
      // The page polled what the backend advertised and started nothing of its own.
      expect(sync.statusReads).toEqual(['/api/sync/tasks/task-account-1'])
      expect(sync.writes).toEqual([])
      expect(clock.waiting()).toBe(1)
      expect(api.profitRequests).toHaveLength(1)
    })

    it('replacesTheProvisionalResultAndOnlyThenRemovesTheIndicator', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()),
        profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      const sync = recordingSyncApi(statesByTask({ 'task-account-1': 'SUCCEEDED' }))
      const { wrapper, clock } = await openScreen(api, sync)

      // The first poll already reported SUCCEEDED, so the cycle resubmitted at once and the final
      // answer is what removed the indicator — not the task's own state.
      expect(api.profitRequests).toHaveLength(2)
      expect(api.profitRequests.at(1)).toEqual({ scope: { kind: 'ALL' }, settings: DEFAULT_SETTINGS })
      expect(tableProfits(wrapper)).toEqual([REFRESHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(exists(wrapper, 'refresh-failed')).toBe(false)
      expect(clock.waiting()).toBe(0)
    })

    it('showsAPendingStateWithNoFabricatedRowForATwoHundredTwoAnswer', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        pendingProfitResponse(),
        profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      const states: Record<string, string> = {}
      const sync = recordingSyncApi(statesByTask(states))
      const { wrapper, clock } = await openScreen(api, sync)

      expect(exists(wrapper, 'pending')).toBe(true)
      expect(text(wrapper, 'pending')).toContain('not finished synchronizing')
      expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(0)
      expect(exists(wrapper, 'profit-table')).toBe(false)
      expect(exists(wrapper, 'empty')).toBe(false)
      expect(exists(wrapper, 'stale-data-indicator')).toBe(true)
      expect(text(wrapper, 'stale-data-explanation')).toContain('no results are shown')
      expect(text(wrapper, 'detail-placeholder')).toContain('nothing to select')

      // The same cycle then renders the usable result the completed work produced.
      states['task-account-1'] = 'SUCCEEDED'
      await clock.tick(3)

      expect(exists(wrapper, 'pending')).toBe(false)
      expect(tableProfits(wrapper)).toEqual([REFRESHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
    })
  })

  describe('following what the backend advertises until the inputs are fresh (AC2)', () => {
    it('followsSuccessiveAccountAndTradingPostWorkAndKeepsTheIndicatorUntilTheFinalAnswer', async () => {
      const api = new FakeCraftingApi()
      const narrowAccount = provisionalFreshness({
        calculationId: 'generation-1',
        outstandingWork: [accountWork('task-account-1', { sources: ['CHARACTERS'] })]
      })
      // The first task succeeded and the required sources are *still* not all fresh: the backend
      // admits a second, differently scoped account task. Task success is not freshness.
      const widerAccount = provisionalFreshness({
        calculationId: 'generation-2',
        outstandingWork: [accountWork('task-account-2', { sources: ['INVENTORY', 'MATERIALS'] })]
      })
      // Account inputs became current, which changed the relevant item set, so Trading Post work
      // is required next — reported by its own mechanism, never merged into the account state.
      const tradingPost = provisionalFreshness({
        calculationId: 'generation-3',
        accountSources: finalFreshness().accountSources,
        tradingPost: uncoveredPrices(),
        outstandingWork: [tradingPostWork('task-prices-1')]
      })
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, narrowAccount),
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, widerAccount),
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, tradingPost),
        profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      const sync = recordingSyncApi(
        statesByTask({
          'task-account-1': 'SUCCEEDED',
          'task-account-2': 'SUCCEEDED',
          'task-prices-1': 'SUCCEEDED'
        })
      )
      const { wrapper, clock } = await openScreen(api, sync)
      // Each later round is paced by one wait, so the test releases them one at a time.
      await clock.tick(5)

      // Every advertised task was polled at the location the backend named, in order, and each
      // completion was followed by a resubmission with the settings currently in effect.
      expect(sync.statusReads).toEqual([
        '/api/sync/tasks/task-account-1',
        '/api/sync/tasks/task-account-2',
        '/api/sync/tasks/task-prices-1'
      ])
      expect(api.profitRequests).toHaveLength(4)
      for (const request of api.profitRequests.slice(1)) {
        expect(request).toEqual({ scope: { kind: 'ALL' }, settings: DEFAULT_SETTINGS })
      }
      expect(sync.writes).toEqual([])
      expect(tableProfits(wrapper)).toEqual([REFRESHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
    })

    it('keepsTheIndicatorUpWhileASucceededTaskLeavesSourcesStale', async () => {
      const api = new FakeCraftingApi()
      const stillStale = provisionalFreshness({
        calculationId: 'generation-2',
        outstandingWork: [accountWork('task-account-2', { taskState: 'PENDING' })]
      })
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()),
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, stillStale)
      ])
      const sync = recordingSyncApi(
        statesByTask({ 'task-account-1': 'SUCCEEDED', 'task-account-2': 'RUNNING' })
      )
      const { wrapper, clock } = await openScreen(api, sync)
      await clock.tick()

      expect(sync.statusReads).toEqual([
        '/api/sync/tasks/task-account-1',
        '/api/sync/tasks/task-account-2'
      ])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(true)
      expect(text(wrapper, 'stale-data-outstanding')).toContain('account data')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
    })

    it('reportsAccountFreshnessAndTradingPostCoverageAsTwoSeparateFacts', async () => {
      const api = new FakeCraftingApi()
      const bothOutstanding = provisionalFreshness({
        tradingPost: uncoveredPrices(),
        outstandingWork: [accountWork('task-account-1'), tradingPostWork('task-prices-1')]
      })
      scriptedProfit(api, [profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, bothOutstanding)])
      const { wrapper } = await openScreen(api, recordingSyncApi(statesByTask({})))

      // The counts are the envelope's own and stay in their own lines: 3 of 4 account sources up to
      // date, and a Trading Post gap of 180 expired plus 25 with no stored price at all. Neither
      // line's numbers leak into the other, which is what keeps the two mechanisms distinguishable.
      const account = text(wrapper, 'stale-data-account')
      const prices = text(wrapper, 'stale-data-trading-post')
      expect(account).toContain('3 of 4 up to date')
      expect(account).toContain('expired: CHARACTERS')
      expect(account).not.toContain('3205')
      expect(prices).toContain('3000 of 3205 required items up to date')
      expect(prices).toContain('expired: 180')
      expect(prices).toContain('no stored price: 25')
      expect(text(wrapper, 'stale-data-outstanding')).toContain('account data')
      expect(text(wrapper, 'stale-data-outstanding')).toContain('Trading Post prices')
    })
  })

  describe('the view the user set up survives replacement (AC3)', () => {
    it('keepsSettingsSearchSortAndSelectionAcrossAnAutomaticReplacement', async () => {
      const api = new FakeCraftingApi()
      const second: CraftingRow = { ...cachedRow, recipeId: 12, outputItemId: 1202, outputName: 'Bowl of Soup' }
      const secondRefreshed: CraftingRow = { ...second, totalProfitCopper: 2_468 }
      api.profitHandler = (request, callIndex) => {
        const settings = request.settings ?? DEFAULT_SETTINGS
        if (callIndex === 0) {
          return Promise.resolve(profitResponse([cachedRow, second], 'ALL', settings, provisionalFreshness()))
        }
        return Promise.resolve(
          profitResponse([refreshedRow, secondRefreshed], 'ALL', settings, finalFreshness())
        )
      }
      const sync = recordingSyncApi(statesByTask({}))
      const { wrapper, clock } = await openScreen(api, sync)

      await wrapper.find('[data-test="search"]').setValue('o')
      await wrapper.find('[data-test="sort-outputName"]').trigger('click')
      await selectRecipe(wrapper, 'Bowl of Soup')
      await wrapper.find('[data-test="setting-allowBuying"]').setValue(true)
      await flushPromises()

      // A foreground request takes over: the user's own settings are what the page asked with.
      expect(api.profitRequests.at(-1)?.settings?.allowBuying).toBe(true)
      expect((wrapper.find('[data-test="search"]').element as HTMLInputElement).value).toBe('o')
      expect(wrapper.find('[data-test="sort-outputName"]').text()).toContain('▲')
      expect(text(wrapper, 'detail-name')).toBe('Bowl of Soup')
      expect(text(wrapper, 'detail-total-profit')).toBe('+24s 68c')
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)

      // Releasing the abandoned cycle's own parked wait changes nothing: it stops at its next
      // checkpoint instead of writing into the context the user has moved to.
      const requestsAfterReplacement = api.profitRequests.length
      await clock.tick(3)
      expect(api.profitRequests).toHaveLength(requestsAfterReplacement)
      // Still the chosen ascending-by-name order, carrying each row's own supplied total.
      expect(rowNames(wrapper)).toEqual(['Bowl of Soup', 'Iron Ingot'])
      expect(tableProfits(wrapper)).toEqual(['+24s 68c', '+43s 21c'])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
    })

    it('fallsBackGracefullyWhenTheReplacementNoLongerOffersTheSelectedRecipe', async () => {
      const api = new FakeCraftingApi()
      const other: CraftingRow = { ...refreshedRow, recipeId: 12, outputItemId: 1202, outputName: 'Bowl of Soup' }
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()),
        profitResponse([other], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      const states: Record<string, string> = {}
      const { wrapper, clock } = await openScreen(api, recordingSyncApi(statesByTask(states)))

      await selectRecipe(wrapper, cachedRow.outputName as string)
      expect(text(wrapper, 'detail-name')).toBe(cachedRow.outputName)

      states['task-account-1'] = 'SUCCEEDED'
      await clock.tick(3)

      expect(rowNames(wrapper)).toEqual(['Bowl of Soup'])
      expect(exists(wrapper, 'detail-name')).toBe(false)
      expect(text(wrapper, 'detail-placeholder')).toContain('Choose a recipe')
      expect(wrapper.findAll('[data-test="profit-row"]').filter((row) => row.classes('selected'))).toHaveLength(0)
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
    })

    it('doesNotLetALateRecoveryAnswerOverwriteANewerSelection', async () => {
      const api = new FakeCraftingApi()
      const resumeFirst: Array<(value: CraftingProfitResponse) => void> = []
      api.profitHandler = (request, callIndex) => {
        if (callIndex === 0) {
          return Promise.resolve(profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()))
        }
        if (callIndex === 1) {
          // The recovery resubmission, held open until after the user has moved on.
          return new Promise<CraftingProfitResponse>((resolve) => resumeFirst.push(resolve))
        }
        return Promise.resolve(
          profitResponse(
            [{ ...refreshedRow, recipeId: 12, outputItemId: 1202, outputName: 'Bowl of Soup' }],
            request.scope?.kind ?? 'DISCIPLINE',
            request.settings ?? DEFAULT_SETTINGS,
            finalFreshness()
          )
        )
      }
      const sync = recordingSyncApi(statesByTask({ 'task-account-1': 'SUCCEEDED' }))
      const { wrapper } = await openScreen(api, sync)

      await wrapper.find('[data-test="scope-selector"]').setValue('DISCIPLINE|Chef')
      await flushPromises()
      expect(rowNames(wrapper)).toEqual(['Bowl of Soup'])

      // The abandoned cycle's answer arrives now, naming the old scope and a provisional envelope.
      resumeFirst.at(0)?.(
        profitResponse([{ ...cachedRow, totalProfitCopper: 9_999 }], 'ALL', DEFAULT_SETTINGS, provisionalFreshness())
      )
      await flushPromises()

      expect(rowNames(wrapper)).toEqual(['Bowl of Soup'])
      expect(tableProfits(wrapper)).toEqual([REFRESHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(exists(wrapper, 'refresh-failed')).toBe(false)
    })

    it('showsADetailFromNewerInputsAgainstItsOwnRowRatherThanTheProvisionalOne', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness())])
      api.resolutionHandler = (request) =>
        Promise.resolve(
          resolutionResponse(request, {
            consistency: 'RECALCULATED_FOR_CHANGED_INPUTS',
            row: recalculatedDetailRow
          })
        )
      const { wrapper } = await openScreen(api, recordingSyncApi(statesByTask({})))
      await selectRecipe(wrapper, cachedRow.outputName as string)

      // The detail request named the generation the table result reported.
      expect(api.resolutionRequests.at(0)?.basedOnCalculationId).toBe(provisionalFreshness().calculationId)
      // The panel reads the answer's own row, says why, and the table keeps its own result.
      expect(text(wrapper, 'detail-total-profit')).toBe(RECALCULATED_DETAIL_PROFIT)
      expect(text(wrapper, 'detail-total-profit')).not.toBe(CACHED_PROFIT)
      expect(text(wrapper, 'detail-recalculated')).toContain('newer calculation')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'resolution-tree')).toBe(true)
    })

    it('requestsAndShowsTheMatchingDetailOnceTheReplacementRowArrives', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()),
        profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      api.resolutionHandler = (request) =>
        Promise.resolve(
          request.basedOnCalculationId === finalFreshness().calculationId
            ? resolutionResponse(request, { consistency: 'FRESH_CALCULATION', row: refreshedRow })
            : resolutionResponse(request, {
                consistency: 'RECALCULATED_FOR_CHANGED_INPUTS',
                row: recalculatedDetailRow
              })
        )
      const states: Record<string, string> = {}
      const { wrapper, clock } = await openScreen(api, recordingSyncApi(statesByTask(states)))
      await selectRecipe(wrapper, cachedRow.outputName as string)
      expect(text(wrapper, 'detail-total-profit')).toBe(RECALCULATED_DETAIL_PROFIT)

      states['task-account-1'] = 'SUCCEEDED'
      await clock.tick(3)

      // The replacement generation got its own detail, and the panel is that pair alone.
      expect(api.resolutionRequests.at(-1)?.basedOnCalculationId).toBe(finalFreshness().calculationId)
      expect(exists(wrapper, 'detail-recalculated')).toBe(false)
      expect(text(wrapper, 'detail-total-profit')).toBe(REFRESHED_PROFIT)
      expect(tableProfits(wrapper)).toEqual([REFRESHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
    })
  })

  describe('bounded recovery, safe failure and explicit retry (AC4)', () => {
    it('stopsAtOneCumulativeFiveMinuteDeadlineThatFurtherTasksDoNotReset', async () => {
      const api = new FakeCraftingApi()
      let generation = 0
      // Every resubmission advertises a *new* task. If the budget were per task this would never
      // end; the one deadline taken when the cycle started is what stops it.
      api.profitHandler = () => {
        generation += 1
        return Promise.resolve(
          profitResponse(
            [cachedRow],
            'ALL',
            DEFAULT_SETTINGS,
            provisionalFreshness({
              calculationId: `generation-${generation}`,
              outstandingWork: [accountWork(`task-account-${generation}`)]
            })
          )
        )
      }
      const sync = recordingSyncApi((statusUrl) => taskState(statusUrl, 'SUCCEEDED'))
      const clock = controlledClock(30_000)
      const { wrapper } = await openScreen(api, sync, clock)

      // Each round is one task followed, one resubmission and one paced wait: 30 s of this clock.
      await clock.tick(20)

      expect(clock.elapsedMs()).toBeGreaterThanOrEqual(RECOVERY_BUDGET_MS)
      expect(clock.elapsedMs()).toBeLessThan(RECOVERY_BUDGET_MS + 60_000)
      expect(new Set(sync.statusReads).size).toBeGreaterThan(1)
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(text(wrapper, 'refresh-failed')).toContain('did not finish')
      expect(text(wrapper, 'refresh-failure-detail')).toContain('within five minutes')
      // The last usable rows are still on screen, and nothing is still animating or parked.
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'refresh-retry')).toBe(true)
      expect(clock.waiting()).toBe(0)
      expect(sync.writes).toEqual([])
    })

    it('stopsAtTheDeadlineWhileATaskStatusRequestIsStillUnanswered', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()),
        profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      const held = deferred<SyncTaskStatus>()
      const sync = recordingSyncApi(() => held.promise)
      const { wrapper, clock } = await openScreen(api, sync)

      // The advertised task was polled and that read has not answered. Nothing is parked in a wait,
      // so no check between awaits can run again: only the budget itself can end this.
      expect(sync.statusReads).toEqual(['/api/sync/tasks/task-account-1'])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(true)
      expect(clock.waiting()).toBe(0)
      expect(clock.pendingAlarms()).toBe(1)

      await clock.advance(RECOVERY_BUDGET_MS)

      expect(clock.elapsedMs()).toBe(RECOVERY_BUDGET_MS)
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(text(wrapper, 'refresh-failed')).toContain('did not finish')
      expect(text(wrapper, 'refresh-failure-detail')).toContain('within five minutes')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'refresh-retry')).toBe(true)
      expect(clock.pendingAlarms()).toBe(0)

      // Nothing presumed the backend had stopped: its answer arrives late and changes nothing. In
      // particular it does not resubmit the calculation, which would be an unbounded cycle again.
      held.resolve(taskState('task-account-1', 'SUCCEEDED'))
      await flushPromises()

      expect(api.profitRequests).toHaveLength(1)
      expect(sync.statusReads).toHaveLength(1)
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(text(wrapper, 'refresh-failure-detail')).toContain('within five minutes')
      expect(clock.waiting()).toBe(0)
      expect(sync.writes).toEqual([])
    })

    it('stopsAtTheDeadlineWhileARecalculationIsStillUnansweredAndIgnoresItsLateAnswer', async () => {
      const api = new FakeCraftingApi()
      const held = deferred<CraftingProfitResponse>()
      api.profitHandler = (_request, callIndex) =>
        callIndex === 0
          ? Promise.resolve(profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()))
          : held.promise
      const sync = recordingSyncApi(statesByTask({ 'task-account-1': 'SUCCEEDED' }))
      const { wrapper, clock } = await openScreen(api, sync)

      // The task succeeded, the cycle resubmitted, and that recalculation has not answered either.
      expect(api.profitRequests).toHaveLength(2)
      expect(exists(wrapper, 'stale-data-indicator')).toBe(true)
      expect(clock.waiting()).toBe(0)
      expect(clock.pendingAlarms()).toBe(1)

      await clock.advance(RECOVERY_BUDGET_MS)

      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(text(wrapper, 'refresh-failure-detail')).toContain('within five minutes')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'refresh-retry')).toBe(true)
      expect(clock.pendingAlarms()).toBe(0)

      // The expired request answers, with a different result and a *final* envelope. It must not
      // replace what is displayed: the cycle it belonged to is over and said so to the user.
      held.resolve(profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness()))
      await flushPromises()

      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(text(wrapper, 'refresh-failure-detail')).toContain('within five minutes')
      expect(api.profitRequests).toHaveLength(2)
      expect(clock.waiting()).toBe(0)
    })

    it('boundsAnExplicitRetryWhoseRecalculationIsStillUnanswered', async () => {
      const api = new FakeCraftingApi()
      const held = deferred<CraftingProfitResponse>()
      api.profitHandler = (_request, callIndex) =>
        callIndex === 0
          ? Promise.resolve(
              profitResponse(
                [cachedRow],
                'ALL',
                DEFAULT_SETTINGS,
                provisionalFreshness({ outstandingWork: [], failures: [] })
              )
            )
          : held.promise
      const { wrapper, clock } = await openScreen(api, recordingSyncApi(statesByTask({})))
      expect(exists(wrapper, 'refresh-retry')).toBe(true)

      await wrapper.find('[data-test="refresh-retry"]').trigger('click')
      await flushPromises()

      // The retry's own request is inside the retry's budget: while it is pending the page is
      // recovering again rather than showing an unexplained result with neither indicator nor
      // retry, and the deadline is already running against it.
      expect(api.profitRequests).toHaveLength(2)
      expect(exists(wrapper, 'stale-data-indicator')).toBe(true)
      expect(exists(wrapper, 'refresh-failed')).toBe(false)
      expect(clock.waiting()).toBe(0)
      expect(clock.pendingAlarms()).toBe(1)

      await clock.advance(RECOVERY_BUDGET_MS)

      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(text(wrapper, 'refresh-failure-detail')).toContain('within five minutes')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'refresh-retry')).toBe(true)
      expect(clock.pendingAlarms()).toBe(0)

      held.resolve(profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness()))
      await flushPromises()

      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(api.profitRequests).toHaveLength(2)
    })

    it('stopsWhenAProvisionalAnswerAdmittedNoRefreshWorkAtAll', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        profitResponse(
          [cachedRow],
          'ALL',
          DEFAULT_SETTINGS,
          provisionalFreshness({ outstandingWork: [], failures: [] })
        )
      ])
      const sync = recordingSyncApi(statesByTask({}))
      const { wrapper, clock } = await openScreen(api, sync)

      // Nothing to poll and nothing to wait for: the page says so instead of animating forever.
      expect(sync.statusReads).toEqual([])
      expect(api.profitRequests).toHaveLength(1)
      expect(text(wrapper, 'refresh-failure-detail')).toContain('admitted no refresh work')
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'refresh-retry')).toBe(true)
      expect(clock.waiting()).toBe(0)
    })

    it('keepsAskingWhileOutstandingWorkHasNoTaskToFollowYet', async () => {
      const api = new FakeCraftingApi()
      // Another entry point holds the mechanism, so the backend reports the demand without a task
      // of its own. There is nothing to poll; the paced round asks the calculation again instead.
      const heldByAnother = provisionalFreshness({
        outstandingWork: [
          accountWork('task-account-1', { taskId: null, taskState: 'NONE', taskStatusUrl: null })
        ]
      })
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, heldByAnother),
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, heldByAnother),
        profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      const sync = recordingSyncApi(statesByTask({}))
      const { wrapper, clock } = await openScreen(api, sync)

      // Nothing was polled, and the first round still asked the calculation again — which is what
      // picks the refresh up when it lands, rather than waiting out the whole budget for a task
      // reference that will never arrive.
      expect(sync.statusReads).toEqual([])
      expect(api.profitRequests).toHaveLength(2)
      expect(exists(wrapper, 'stale-data-indicator')).toBe(true)
      expect(text(wrapper, 'stale-data-outstanding')).toContain('account data')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])

      // The next round is paced by one wait and reaches the final answer.
      await clock.tick()
      expect(api.profitRequests).toHaveLength(3)
      expect(tableProfits(wrapper)).toEqual([REFRESHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(sync.statusReads).toEqual([])
    })

    it('stopsWhenTheAdvertisedTaskIsNoLongerKnownToTheBackend', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        profitResponse(
          [cachedRow],
          'ALL',
          DEFAULT_SETTINGS,
          provisionalFreshness({
            outstandingWork: [accountWork('task-account-1', { taskState: 'UNKNOWN' })]
          })
        )
      ])
      const sync = recordingSyncApi(statesByTask({}))
      const { wrapper } = await openScreen(api, sync)

      expect(sync.statusReads).toEqual([])
      expect(text(wrapper, 'refresh-failed')).toContain('did not finish')
      expect(text(wrapper, 'refresh-failure-detail')).toContain('no longer known')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
    })

    it('stopsOnATransportFailureWhilePollingAndKeepsTheCachedRows', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness())])
      const sync = recordingSyncApi(() =>
        Promise.reject(new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503))
      )
      const { wrapper, clock } = await openScreen(api, sync)

      expect(text(wrapper, 'refresh-failure-detail')).toContain('DATA_STORE_UNAVAILABLE')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(exists(wrapper, 'refresh-retry')).toBe(true)
      expect(clock.waiting()).toBe(0)
    })

    it('reportsTheBackendsOwnFailureItemAndStaysExplicitWithNoCachedResult', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        pendingProfitResponse(
          provisionalFreshness({
            resultAvailable: false,
            outstandingWork: [],
            accountSources: ACCOUNT_SOURCES.map((source) => ({ source, fetchedAt: null, state: 'MISSING' })),
            failures: [
              {
                kind: 'ACCOUNT',
                taskId: 'task-account-1',
                taskStatusUrl: '/api/sync/tasks/task-account-1',
                code: 'ACCOUNT_REFRESH_FAILED',
                message: 'The account refresh task ended without completing its sources.',
                retryable: true
              }
            ]
          })
        )
      ])
      const { wrapper } = await openScreen(api, recordingSyncApi(statesByTask({})))

      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(text(wrapper, 'refresh-failed')).toContain('No results could be calculated')
      expect(text(wrapper, 'refresh-failure-detail')).toContain('ACCOUNT_REFRESH_FAILED')
      expect(wrapper.findAll('[data-test="profit-row"]')).toHaveLength(0)
      expect(exists(wrapper, 'pending')).toBe(true)
      expect(exists(wrapper, 'empty')).toBe(false)
      expect(text(wrapper, 'refresh-failed-account')).toContain('never synchronized')
    })

    it('reportsAPollFailureThatIsNotAnErrorObjectRatherThanFailingSilently', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness())])
      // Transport rejections are unvalidated: whatever was thrown has to reach the user as a
      // reason, not leave the page animating with nothing to read.
      const sync = recordingSyncApi(() => Promise.reject('the fetch was aborted'))
      const { wrapper, clock } = await openScreen(api, sync)

      expect(text(wrapper, 'refresh-failure-detail')).toContain('the fetch was aborted')
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(clock.waiting()).toBe(0)
    })

    it('reportsAFailedRefreshTaskWithTheBackendsOwnWording', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness())])
      const sync = recordingSyncApi(() => ({
        ...taskState('task-account-1', 'FAILED'),
        failure: { error: 'UPSTREAM_UNAVAILABLE', message: 'The GW2 API did not answer.' }
      }))
      const { wrapper } = await openScreen(api, sync)

      expect(text(wrapper, 'refresh-failure-detail')).toContain('UPSTREAM_UNAVAILABLE')
      expect(text(wrapper, 'refresh-failure-detail')).toContain('The GW2 API did not answer.')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
    })

    it('startsAFreshBoundedCycleOnAnExplicitRetryWithoutStartingAnyRefreshItself', async () => {
      const api = new FakeCraftingApi()
      // The first cycle dies on a transport failure; the retry's polls then succeed.
      let pollsFail = true
      const polling = recordingSyncApi((statusUrl) => {
        if (pollsFail) {
          return Promise.reject(new ApiRequestError('Gateway timeout', 'UPSTREAM_TIMEOUT', 504))
        }
        return taskState(statusUrl, 'SUCCEEDED')
      })
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()),
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()),
        profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      const { wrapper, clock } = await openScreen(api, polling)
      expect(exists(wrapper, 'refresh-retry')).toBe(true)
      const requestsBeforeRetry = api.profitRequests.length

      pollsFail = false
      await wrapper.find('[data-test="refresh-retry"]').trigger('click')
      await flushPromises()

      // The retry asked for the calculation again — which is what makes the backend recheck its own
      // freshness — rather than triggering a synchronization of its own.
      expect(api.profitRequests.length).toBeGreaterThan(requestsBeforeRetry)
      expect(polling.writes).toEqual([])
      expect(exists(wrapper, 'refresh-failed')).toBe(false)
      expect(tableProfits(wrapper)).toEqual([REFRESHED_PROFIT])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(clock.waiting()).toBe(0)
    })

    it('reportsAFailedRetryRequestInsteadOfSilentlyEndingTheCycle', async () => {
      const api = new FakeCraftingApi()
      api.profitHandler = (_request, callIndex) =>
        callIndex === 0
          ? Promise.resolve(
              profitResponse(
                [cachedRow],
                'ALL',
                DEFAULT_SETTINGS,
                provisionalFreshness({ outstandingWork: [], failures: [] })
              )
            )
          : Promise.reject(new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503))
      const { wrapper } = await openScreen(api, recordingSyncApi(statesByTask({})))
      expect(exists(wrapper, 'refresh-retry')).toBe(true)

      await wrapper.find('[data-test="refresh-retry"]').trigger('click')
      await flushPromises()

      // The retry's own request failed. That is reported where the retry is, and the cached rows
      // stay: a background resubmission never clears the result the user can still read.
      expect(text(wrapper, 'refresh-failure-detail')).toContain('DATA_STORE_UNAVAILABLE')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(exists(wrapper, 'request-error')).toBe(false)
      expect(exists(wrapper, 'refresh-retry')).toBe(true)
    })

    it('firesTheBrowsersOwnBudgetTimerAndCancelsItWithTheCycle', async () => {
      // The clock every test above injects is not what runs in the browser. This is: the page's
      // own budget timer, which has to fire without any request answering and has to stop when the
      // cycle ends before it, so a finished refresh leaves no timer behind.
      vi.useFakeTimers()
      try {
        let fired = false
        const running = timerAlarm(RECOVERY_BUDGET_MS)
        void running.expired.then(() => {
          fired = true
        })

        vi.advanceTimersByTime(RECOVERY_BUDGET_MS - 1)
        await Promise.resolve()
        expect(fired).toBe(false)

        vi.advanceTimersByTime(1)
        await Promise.resolve()
        expect(fired).toBe(true)

        let cancelledFired = false
        const cancelled = timerAlarm(RECOVERY_BUDGET_MS)
        void cancelled.expired.then(() => {
          cancelledFired = true
        })
        cancelled.cancel()

        vi.advanceTimersByTime(RECOVERY_BUDGET_MS * 2)
        await Promise.resolve()
        expect(cancelledFired).toBe(false)
      } finally {
        vi.useRealTimers()
      }
    })

    it('keepsTheRetryBoundedToOneCycleRatherThanOverlappingTheAbandonedOne', async () => {
      const api = new FakeCraftingApi()
      let generation = 0
      api.profitHandler = () => {
        generation += 1
        return Promise.resolve(
          profitResponse(
            [cachedRow],
            'ALL',
            DEFAULT_SETTINGS,
            provisionalFreshness({ calculationId: `generation-${generation}` })
          )
        )
      }
      const sync = recordingSyncApi(statesByTask({}))
      const { wrapper, clock } = await openScreen(api, sync)
      expect(sync.statusReads).toHaveLength(1)

      await wrapper.find('[data-test="refresh-data-and-results"]').trigger('click')
      await flushPromises()
      const pollsAfterReload = sync.statusReads.length
      expect(pollsAfterReload).toBe(2)

      // Both cycles' parked waits are released at once. Only one cycle polls again: the abandoned
      // one stops at its next checkpoint rather than running beside its replacement.
      await clock.tick()
      expect(sync.statusReads).toHaveLength(pollsAfterReload + 1)
      expect(exists(wrapper, 'stale-data-indicator')).toBe(true)
      expect(sync.writes).toEqual([])
    })
  })

  describe('backend values and policies are untouched (AC5)', () => {
    it('rendersTheSuppliedMonetaryValuesStatesAndQuantitiesUnchanged', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness())])
      api.resolutionHandler = (request) =>
        Promise.resolve(resolutionResponse(request, { row: cachedRow }))
      const { wrapper } = await openScreen(api, recordingSyncApi(statesByTask({})))
      await selectRecipe(wrapper, cachedRow.outputName as string)

      // Every figure is the fixture's own, none of them a product the page could have formed:
      // 1 234 not 7 x 100, 9 876 not 7 x 380, and the per-craft own-materials value unscaled.
      expect(text(wrapper, 'detail-total-profit')).toBe(CACHED_PROFIT)
      expect(text(wrapper, 'detail-total-sell-value')).toBe('98s 76c')
      expect(text(wrapper, 'detail-own-material-cost')).toBe('40s 0c')
      expect(text(wrapper, 'detail-buy-cost')).toBe('25s 0c')
      expect(text(wrapper, 'detail-calculation')).toContain('No. craftable Items 7')
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
      expect(
        wrapper.findAll('[data-test="total-sell-value"]').map((element) => element.text())
      ).toEqual(['98s 76c'])
      // A provisional result is still the ordinary authoritative one: the row carries no warning of
      // its own and the states the domain reported are unchanged.
      expect(exists(wrapper, 'row-diagnostic')).toBe(false)
      expect(text(wrapper, 'summary')).toContain('1 calculated for this scope')
    })

    it('needsNoSystemStatusVisitAndNeverCallsASynchronizationTrigger', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, provisionalFreshness()),
        profitResponse([refreshedRow], 'ALL', DEFAULT_SETTINGS, finalFreshness())
      ])
      const sync = recordingSyncApi(statesByTask({ 'task-account-1': 'SUCCEEDED' }))
      const { wrapper } = await openScreen(api, sync)

      await wrapper.find('[data-test="refresh-data-and-results"]').trigger('click')
      await flushPromises()

      expect(sync.writes).toEqual([])
      expect(wrapper.text()).not.toContain('System Status')
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(tableProfits(wrapper)).toEqual([REFRESHED_PROFIT])
    })

    it('keepsAnUnknownAccountSourceStateVisibleRatherThanReadingItAsFreshness', async () => {
      const api = new FakeCraftingApi()
      const unknownState: CalculationFreshness = provisionalFreshness({
        accountSources: [
          { source: 'CHARACTERS', fetchedAt: '2026-10-13T08:00:00Z', state: 'SOME_STATE_ADDED_LATER' },
          { source: 'INVENTORY', fetchedAt: '2026-10-13T08:59:00Z', state: 'FRESH' }
        ]
      })
      scriptedProfit(api, [profitResponse([cachedRow], 'ALL', DEFAULT_SETTINGS, unknownState)])
      const { wrapper } = await openScreen(api, recordingSyncApi(statesByTask({})))

      const account = text(wrapper, 'stale-data-account')
      expect(account).toContain('1 of 2 up to date')
      expect(account).toContain('CHARACTERS: SOME_STATE_ADDED_LATER')
    })

    it('keepsAnUnknownRefreshMechanismAndAnUnreportedSourceSetVisible', async () => {
      const api = new FakeCraftingApi()
      scriptedProfit(api, [
        profitResponse(
          [cachedRow],
          'ALL',
          DEFAULT_SETTINGS,
          provisionalFreshness({
            accountSources: [],
            outstandingWork: [accountWork('task-other-1', { kind: 'SOME_MECHANISM_ADDED_LATER' })]
          })
        )
      ])
      const { wrapper } = await openScreen(api, recordingSyncApi(statesByTask({})))

      // A mechanism this client has no wording for is named as the backend named it, and an empty
      // required-source list is reported as such rather than read as "everything is up to date".
      expect(text(wrapper, 'stale-data-outstanding')).toContain('SOME_MECHANISM_ADDED_LATER')
      expect(text(wrapper, 'stale-data-account')).toContain('no required source was reported')
      expect(text(wrapper, 'stale-data-account')).not.toContain('up to date')
      // The covered-coverage wording, which the uncovered case above does not reach.
      expect(text(wrapper, 'stale-data-trading-post')).toContain('all 3205 required items covered')
    })

    it('omitsTheGenerationFromADetailRequestWhenTheResponseNamedNone', async () => {
      const api = new FakeCraftingApi()
      // A response carrying no envelope at all: the detail request must then carry no generation
      // rather than a null, which is how this request contract expresses "not supplied".
      api.profitHandler = (request) =>
        Promise.resolve({
          ...profitResponse([cachedRow], 'ALL', request.settings ?? DEFAULT_SETTINGS),
          freshness: undefined as unknown as CalculationFreshness
        })
      const { wrapper } = await openScreen(api, recordingSyncApi(statesByTask({})))
      await selectRecipe(wrapper, cachedRow.outputName as string)

      expect(api.resolutionRequests).toHaveLength(1)
      expect(Object.keys(api.resolutionRequests[0] ?? {}).sort()).toEqual(['calculation', 'recipeId'])
      expect(exists(wrapper, 'stale-data-indicator')).toBe(false)
      expect(exists(wrapper, 'refresh-failed')).toBe(false)
      expect(tableProfits(wrapper)).toEqual([CACHED_PROFIT])
    })
  })
})
