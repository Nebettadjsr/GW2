/** Versioned static preset definitions: explicit item IDs only, never inferred from metadata. */
export const MATERIAL_PRESETS_VERSION = 1

export interface MaterialPreset {
  readonly id: string
  readonly categoryId: number
  readonly label: string
  readonly tooltip: string
  readonly itemIds: readonly number[]
}

function tier(n: number, ids: readonly number[]): MaterialPreset {
  return { id: `t${n}`, categoryId: 29, label: `T${n}`, tooltip: `Select or deselect the eight tier ${n} intermediate materials (blood, bone, claw, dust, fang, scale, totem, venom sac).`, itemIds: ids }
}

export const MATERIAL_PRESETS: readonly MaterialPreset[] = [
  tier(1, [24290, 24342, 24346, 24272, 24352, 24284, 24296, 24278]),
  tier(2, [24291, 24343, 24347, 24273, 24353, 24285, 24297, 24279]),
  tier(3, [24292, 24344, 24348, 24274, 24354, 24286, 24298, 24280]),
  tier(4, [24293, 24345, 24349, 24275, 24355, 24287, 24363, 24281]),
  tier(5, [24294, 24341, 24350, 24276, 24356, 24288, 24299, 24282]),
  tier(6, [24295, 24358, 24351, 24277, 24357, 24289, 24300, 24283]),
  {
    id: 'extra',
    categoryId: 37,
    label: 'Clovers, coins, ecto & obsidian',
    tooltip: 'Select or deselect Mystic Clover, Mystic Coin, Glob of Ectoplasm and Obsidian Shard.',
    itemIds: [19675, 19976, 19721, 19925]
  }
]
