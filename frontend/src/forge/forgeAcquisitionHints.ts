/** Display metadata only; quantities and costs come from the backend plan. */
export interface ForgeMerchant {
  name: string
  wikiUrl: string
  waypoint: { name: string; wikiUrl: string; chatCode: string }
  condition?: string
}

const miyani: ForgeMerchant = {
  name: 'Miyani',
  wikiUrl: 'https://wiki.guildwars2.com/wiki/Miyani',
  waypoint: {
    name: 'Trader’s Forum Waypoint',
    wikiUrl: 'https://wiki.guildwars2.com/wiki/Trader%27s_Forum_Waypoint',
    chatCode: '[&BBAEAAA=]'
  }
}

const merchants: Readonly<Record<number, ForgeMerchant>> = {
  20797: miyani,
  20796: miyani,
  20799: miyani,
  20852: miyani,
  19663: miyani,
  79418: { ...miyani, condition: 'Scholar of Secrets mastery' },
  19925: {
    name: 'Tactician Deathstrider',
    wikiUrl: 'https://wiki.guildwars2.com/wiki/Tactician_Deathstrider',
    waypoint: {
      name: 'Glorious Victory Waypoint',
      wikiUrl: 'https://wiki.guildwars2.com/wiki/Glorious_Victory_Waypoint',
      chatCode: '[&BPoCAAA=]'
    },
    condition: 'Temple secured'
  }
}

export function forgeMerchant(itemId: number): ForgeMerchant | undefined {
  return merchants[itemId]
}
