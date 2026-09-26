import { describe, expect, it } from 'vitest'
import { effectScope } from 'vue'
import { ApiRequestError } from '@/api/http'
import type { BankContents } from '@/api/types'
import { useAccountRead, type AccountReadState } from '../useAccountRead'
import { bankWithEmptySlots, bankWithoutSlots, deferred } from './accountFixtures'

/**
 * The read state a screen is built on, exercised where a screen cannot reach it: two reads in
 * flight at once, and an answer arriving after the screen it belongs to is gone (which is what
 * navigating away does, since the selected screen is the only one mounted).
 */
function runInScope(
  read: () => Promise<BankContents>
): { state: AccountReadState<BankContents>; stop: () => void } {
  const scope = effectScope()
  const state = scope.run(() => useAccountRead<BankContents>(read))
  if (state === undefined) throw new Error('The scope produced no state.')
  return { state, stop: () => scope.stop() }
}

describe('useAccountRead', () => {
  it('startsALoadThatClearsTheEarlierAnswerRatherThanShowingItAsCurrent', async () => {
    const pending = deferred<BankContents>()
    let answer: Promise<BankContents> = Promise.resolve(bankWithEmptySlots)
    const { state } = runInScope(() => answer)

    await state.load()
    expect(state.data.value).toBe(bankWithEmptySlots)

    answer = pending.promise
    const reload = state.load()
    expect(state.phase.value).toBe('loading')
    expect(state.data.value).toBeNull()

    pending.resolve(bankWithoutSlots)
    await reload
    expect(state.data.value).toBe(bankWithoutSlots)
    expect(state.phase.value).toBe('loaded')
  })

  it('discardsASupersededAnswerThatArrivesAfterANewerOne', async () => {
    const slow = deferred<BankContents>()
    const answers: Array<Promise<BankContents>> = [slow.promise, Promise.resolve(bankWithoutSlots)]
    let callIndex = 0
    const { state } = runInScope(() => answers[callIndex++] ?? Promise.resolve(bankWithEmptySlots))

    const first = state.load()
    await state.load()
    expect(state.data.value).toBe(bankWithoutSlots)

    slow.resolve(bankWithEmptySlots)
    await first

    expect(state.data.value).toBe(bankWithoutSlots)
    expect(state.phase.value).toBe('loaded')
  })

  it('discardsASupersededFailureSoItCannotReplaceTheCurrentAnswer', async () => {
    const slow = deferred<BankContents>()
    const answers: Array<Promise<BankContents>> = [slow.promise, Promise.resolve(bankWithoutSlots)]
    let callIndex = 0
    const { state } = runInScope(() => answers[callIndex++] ?? Promise.resolve(bankWithEmptySlots))

    const first = state.load()
    await state.load()

    slow.reject(new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503))
    await first

    expect(state.failure.value).toBeNull()
    expect(state.phase.value).toBe('loaded')
    expect(state.data.value).toBe(bankWithoutSlots)
  })

  it('ignoresAnAnswerThatArrivesAfterTheScreenIsGone', async () => {
    const pending = deferred<BankContents>()
    const { state, stop } = runInScope(() => pending.promise)

    const inFlight = state.load()
    stop()
    pending.resolve(bankWithEmptySlots)
    await inFlight

    expect(state.data.value).toBeNull()
    expect(state.phase.value).toBe('loading')
    expect(state.failure.value).toBeNull()
  })

  it('ignoresAFailureThatArrivesAfterTheScreenIsGone', async () => {
    const pending = deferred<BankContents>()
    const { state, stop } = runInScope(() => pending.promise)

    const inFlight = state.load()
    stop()
    pending.reject(new ApiRequestError('Reading the account failed.', 'ACCOUNT_READ_FAILED', 500))
    await inFlight

    expect(state.failure.value).toBeNull()
    expect(state.phase.value).toBe('loading')
  })

  it('reportsTheBackendCodeAndMessageItWasGiven', async () => {
    const { state } = runInScope(() =>
      Promise.reject(new ApiRequestError('The data store is unavailable.', 'DATA_STORE_UNAVAILABLE', 503))
    )

    await state.load()

    expect(state.failure.value).toEqual({
      code: 'DATA_STORE_UNAVAILABLE',
      message: 'The data store is unavailable.'
    })
    expect(state.data.value).toBeNull()
  })

  it('namesANonBackendFailureAsThisClientsOwn', async () => {
    const { state } = runInScope(() => Promise.reject(new TypeError('parse failed')))

    await state.load()

    expect(state.failure.value?.code).toBe('CLIENT_ERROR')
    expect(state.failure.value?.message).toBe('parse failed')
  })
})
