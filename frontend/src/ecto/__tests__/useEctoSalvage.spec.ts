import { describe, expect, it } from 'vitest'
import { effectScope } from 'vue'
import { ApiRequestError } from '@/api/http'
import type { EctoSalvage } from '@/api/types'
import { useEctoSalvage, type EctoSalvageState } from '../useEctoSalvage'
import { deferred, ectoSalvage, reloadedEctoSalvage } from './ectoFixtures'

/**
 * The request state behind the Ectoplasm screen (`CURRENT_ARCHITECTURE.md` 5.15).
 *
 * Request isolation is a property of which answers are *refused*, so the promises here are resolved
 * by hand rather than awaited into existence (`TEST_STRATEGY.md` 12.1). Nothing here mounts a
 * component or reaches a backend.
 */
function inScope(
  build: () => EctoSalvageState
): { state: EctoSalvageState; dispose: () => void } {
  const scope = effectScope()
  const state = scope.run(build)
  if (state === undefined) throw new Error('the scope produced no state')
  return { state, dispose: () => scope.stop() }
}

describe('useEctoSalvage', () => {
  it('startsLoadingAndKeepsNoDataUntilTheCalculationAnswers', async () => {
    const pending = deferred<EctoSalvage>()
    const { state } = inScope(() => useEctoSalvage(() => pending.promise))

    const running = state.load()

    expect(state.phase.value).toBe('loading')
    expect(state.data.value).toBeNull()
    expect(state.failure.value).toBeNull()

    pending.resolve(ectoSalvage)
    await running

    expect(state.phase.value).toBe('loaded')
    expect(state.data.value).toBe(ectoSalvage)
  })

  it('suppressesASecondCalculationWhileTheFirstIsStillInFlight', async () => {
    const pending = deferred<EctoSalvage>()
    let calls = 0
    const { state } = inScope(() =>
      useEctoSalvage(() => {
        calls += 1
        return pending.promise
      })
    )

    const first = state.load()
    // A repeat that really reaches the state — a double click, a repeated key, a second caller — is
    // refused rather than turned into a second live Trading Post lookup on the backend.
    await state.load()
    await state.load()

    expect(calls).toBe(1)

    pending.resolve(ectoSalvage)
    await first

    expect(calls).toBe(1)
    expect(state.data.value).toBe(ectoSalvage)
  })

  it('allowsAFurtherCalculationOnceTheCurrentOneHasAnswered', async () => {
    let calls = 0
    const answers = [ectoSalvage, reloadedEctoSalvage]
    const { state } = inScope(() =>
      useEctoSalvage(() => {
        const answer = answers[calls] ?? reloadedEctoSalvage
        calls += 1
        return Promise.resolve(answer)
      })
    )

    await state.load()
    await state.load()

    expect(calls).toBe(2)
    expect(state.data.value).toBe(reloadedEctoSalvage)
  })

  it('keepsAFailureApartFromAResultAndDropsTheEarlierAnswer', async () => {
    let calls = 0
    const { state } = inScope(() =>
      useEctoSalvage(() => {
        calls += 1
        return calls === 1
          ? Promise.resolve(ectoSalvage)
          : Promise.reject(new ApiRequestError('unavailable', 'PRICE_SOURCE_UNAVAILABLE', 502))
      })
    )

    await state.load()
    expect(state.data.value).toBe(ectoSalvage)

    await state.load()

    expect(state.phase.value).toBe('failed')
    expect(state.failure.value).toEqual({
      code: 'PRICE_SOURCE_UNAVAILABLE',
      message: 'unavailable'
    })
    // The previous answer is gone: a failed reload must not leave older prices on screen as fresh.
    expect(state.data.value).toBeNull()
  })

  it('namesATransportFailureItselfWhenTheBackendSuppliedNoCode', async () => {
    const { state } = inScope(() => useEctoSalvage(() => Promise.reject(new Error('boom'))))

    await state.load()

    expect(state.failure.value).toEqual({ code: 'CLIENT_ERROR', message: 'boom' })
    expect(state.phase.value).toBe('failed')
  })

  it('refusesAnAnswerThatArrivesAfterTheOwningScreenIsGone', async () => {
    const pending = deferred<EctoSalvage>()
    const { state, dispose } = inScope(() => useEctoSalvage(() => pending.promise))

    const running = state.load()
    dispose()
    pending.resolve(ectoSalvage)
    await running

    expect(state.data.value).toBeNull()
    expect(state.phase.value).toBe('loading')
  })

  it('refusesAFailureThatArrivesAfterTheOwningScreenIsGone', async () => {
    const pending = deferred<EctoSalvage>()
    const { state, dispose } = inScope(() => useEctoSalvage(() => pending.promise))

    const running = state.load()
    dispose()
    pending.reject(new ApiRequestError('too late', 'PRICE_SOURCE_UNAVAILABLE', 502))
    await running

    expect(state.failure.value).toBeNull()
    expect(state.phase.value).toBe('loading')
  })
})
