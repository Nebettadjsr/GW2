/** Player-facing source hints, not acquisition recipes or inputs to the calculation. */
export interface ForgeAcquisitionHint {
  action: string
  source: string
  detail: string
  href: string
}

// Verified against the linked official Wiki pages. These per-item hints never alter plan totals.
const hints: Readonly<Record<number, ForgeAcquisitionHint>> = {
  20797: {
    action: 'Buy', source: 'Miyani / Mystic Forge Attendant',
    detail: '200 Spirit Shards each.', href: 'https://wiki.guildwars2.com/wiki/Bloodstone_Shard'
  },
  19925: {
    action: 'Buy', source: 'Tactician Deathstrider · Straits of Devastation',
    detail: '2,100 Karma each at the Temple of Balthazar, while the temple is secured.',
    href: 'https://wiki.guildwars2.com/wiki/Obsidian_Shard#Acquisition'
  },
  19675: {
    action: 'Acquire', source: 'Mystic Forge / other sources',
    detail: 'Forge attempts use Obsidian Shards, Mystic Coins, ectoplasm and Philosopher’s Stones or Mystic Crystals. The outcome is random; vendor and reward options are also available.',
    href: 'https://wiki.guildwars2.com/wiki/Mystic_Clover#Acquisition'
  }
}

export function forgeAcquisitionHint(itemId: number): ForgeAcquisitionHint | undefined {
  return hints[itemId]
}
