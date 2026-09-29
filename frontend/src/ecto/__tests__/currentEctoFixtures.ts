/** Browser-facing inputs for the current, locally calculated Ectoplasm screen. */
export const ECTO_ID = 19721
export const DUST_ID = 24277
export const SALVAGE_TOOL_IDS = [44602, 23041, 89409, 67027, 19986]
export const METADATA_IDS = [ECTO_ID, DUST_ID, ...SALVAGE_TOOL_IDS]

export const itemMetadata = {
  items: METADATA_IDS.map((itemId) => ({
    itemId,
    name: itemId === ECTO_ID ? 'Glob of Ectoplasm' : itemId === DUST_ID ? 'Pile of Crystalline Dust' : `Tool ${itemId}`,
    iconUrl: `/api/items/${itemId}/icon/source-${itemId}.png`
  }))
}

export const itemPrices = {
  prices: [
    { itemId: ECTO_ID, buyUnitCopper: 100, sellUnitCopper: 120 },
    { itemId: DUST_ID, buyUnitCopper: 200, sellUnitCopper: 240 }
  ]
}

// Synthetic service response: tests check the browser's use of thresholds, not the canonical table.
export const accountLuck = {
  consumedLuck: 14_134,
  currentLuckMagicFindPercent: 50,
  cumulativeLuckForCurrentPercent: 13_790,
  nextMagicFindPercent: 51,
  cumulativeLuckForNextPercent: 14_550,
  luckRemainingToNextPercent: 416,
  luckRemainingToCap: 4_295_450 - 14_134,
  cumulativeLuckForCap: 4_295_450,
  fetchedAt: '2026-09-29T00:00:00Z',
  targets: [
    { kind: 'PLUS_5', magicFindPercent: 55, cumulativeLuck: 17_930, luckRemaining: 17_930 - 14_134 },
    { kind: 'PLUS_10', magicFindPercent: 60, cumulativeLuck: 23_050, luckRemaining: 23_050 - 14_134 },
    { kind: 'CAP', magicFindPercent: 300, cumulativeLuck: 4_295_450, luckRemaining: 4_295_450 - 14_134 }
  ]
}

export const cappedAccountLuck = {
  ...accountLuck,
  consumedLuck: 4_295_450,
  currentLuckMagicFindPercent: 300,
  cumulativeLuckForCurrentPercent: 4_295_450,
  nextMagicFindPercent: null,
  cumulativeLuckForNextPercent: null,
  luckRemainingToNextPercent: 0,
  luckRemainingToCap: 0,
  targets: accountLuck.targets.map((target) => ({
    ...target,
    magicFindPercent: 300,
    cumulativeLuck: 4_295_450,
    luckRemaining: 0
  }))
}
