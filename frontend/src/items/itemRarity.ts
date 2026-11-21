const COLORS: Readonly<Record<string, string>> = {
  Junk: '#aaa', Basic: '#eee', Fine: '#5599ff', Masterwork: '#33cc11',
  Rare: '#ffdd22', Exotic: '#ffaa00', Ascended: '#ff55aa', Legendary: '#aa77ff'
}

export function itemRarityColor(rarity: string | null | undefined): string | undefined {
  return rarity ? COLORS[rarity] : undefined
}
