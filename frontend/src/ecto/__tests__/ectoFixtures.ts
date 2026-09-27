import type { EctoApi } from '@/api/ectoApi'
import type { EctoSalvage } from '@/api/types'

/**
 * Controlled Ectoplasm responses (`TEST_STRATEGY.md` 12.1).
 *
 * The numbers are deliberately incoherent: no field is the arithmetic consequence of any other, in
 * any scenario. The recovered Dust value is not the net unit price scaled by the expected yield, the
 * profit is not the negated net cost, and the Luck cost is not the net cost times fifty. A screen
 * that recomputed any of them would therefore render something other than what was supplied and
 * fail, instead of agreeing with itself by coincidence.
 */
export const ectoSalvage: EctoSalvage = {
  resultAvailable: true,
  ectoItemId: 19721,
  dustItemId: 24277,
  assumptions: {
    expectedLuckPerEcto: 20,
    expectedDustPerEcto: 0.75,
    // Deliberately not 15: a screen that stated the fee from its own knowledge rather than from the
    // backend would print the wrong number here.
    tradingPostSellFeePercent: 12,
    ectosPer1000Luck: 50
  },
  instantBuyInstantSell: {
    ectoAcquisitionCostCopper: 1111,
    dustGrossUnitPriceCopper: 2222,
    dustNetUnitPriceCopper: 3333,
    netValueOfRecoveredDustCopper: 4444,
    netCostPerEctoCopper: 5555,
    profitPerEctoCopper: -6666,
    costPer1000LuckCopper: 7777
  },
  instantBuyListingSell: {
    ectoAcquisitionCostCopper: 1212,
    dustGrossUnitPriceCopper: 2323,
    dustNetUnitPriceCopper: 3434,
    netValueOfRecoveredDustCopper: 4545,
    netCostPerEctoCopper: -5656,
    profitPerEctoCopper: 6767,
    costPer1000LuckCopper: -7878
  },
  listingBuyInstantSell: {
    ectoAcquisitionCostCopper: 1313,
    dustGrossUnitPriceCopper: 2424,
    // A supplied zero, which must stay a zero rather than becoming the missing-value marker.
    dustNetUnitPriceCopper: 0,
    netValueOfRecoveredDustCopper: 4646,
    netCostPerEctoCopper: 5757,
    profitPerEctoCopper: 0,
    costPer1000LuckCopper: 7979
  },
  listingBuyListingSell: {
    ectoAcquisitionCostCopper: 1414,
    dustGrossUnitPriceCopper: 2525,
    dustNetUnitPriceCopper: 3636,
    netValueOfRecoveredDustCopper: 4747,
    netCostPerEctoCopper: 5858,
    profitPerEctoCopper: 6969,
    costPer1000LuckCopper: 8080
  }
}

/** A second calculation with different prices, for reload checks. */
export const reloadedEctoSalvage: EctoSalvage = {
  ...ectoSalvage,
  instantBuyInstantSell: {
    ectoAcquisitionCostCopper: 9111,
    dustGrossUnitPriceCopper: 9222,
    dustNetUnitPriceCopper: 9333,
    netValueOfRecoveredDustCopper: 9444,
    netCostPerEctoCopper: 9555,
    profitPerEctoCopper: 9666,
    costPer1000LuckCopper: 9777
  }
}

/** The completed calculation the Trading Post had no usable quotes for (`DOMAIN_SPEC.md` 21). */
export const unavailableEctoSalvage: EctoSalvage = {
  resultAvailable: false,
  ectoItemId: 19721,
  dustItemId: 24277,
  assumptions: ectoSalvage.assumptions,
  instantBuyInstantSell: null,
  instantBuyListingSell: null,
  listingBuyInstantSell: null,
  listingBuyListingSell: null
}

export interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (cause: unknown) => void
}

/** A promise resolved by hand, so a test can observe the in-flight state and late answers. */
export function deferred<T>(): Deferred<T> {
  let resolve: (value: T) => void = () => undefined
  let reject: (cause: unknown) => void = () => undefined
  const promise = new Promise<T>((resolveWith, rejectWith) => {
    resolve = resolveWith
    reject = rejectWith
  })
  return { promise, resolve, reject }
}

/**
 * Stands in for the real client: it counts the calculations the screen asked for, so a test can
 * assert both that opening the screen calculates once and that a duplicate reload does not.
 */
export class FakeEctoApi implements EctoApi {
  salvageCalls = 0
  salvageHandler: (callIndex: number) => Promise<EctoSalvage> = () => Promise.resolve(ectoSalvage)

  loadSalvage(): Promise<EctoSalvage> {
    const callIndex = this.salvageCalls
    this.salvageCalls += 1
    return this.salvageHandler(callIndex)
  }
}
