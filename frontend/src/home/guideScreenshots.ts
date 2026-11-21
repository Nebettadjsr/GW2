import unidentifiedGear from './images/unidentified-gear.webp?no-inline'
import salvageMenu from './images/salvage-menu.webp?no-inline'
import consumeLuck from './images/consume-luck.webp?no-inline'
import trophyVendor from './images/trophy-vendor.webp?no-inline'
import trophyVendorSmall from './images/trophy-vendor-480.webp?no-inline'
import trophyMaterials from './images/trophy-materials.webp?no-inline'
import trophyMaterialsSmall from './images/trophy-materials-640.webp?no-inline'
import trophyMaterialsMedium from './images/trophy-materials-960.webp?no-inline'
import trophySale from './images/trophy-sale.webp?no-inline'
import intermediateMaterials from './images/intermediate-crafting-materials.webp?no-inline'
import intermediateMaterialsSmall from './images/intermediate-crafting-materials-320.webp?no-inline'
import intermediateMaterialsMedium from './images/intermediate-crafting-materials-480.webp?no-inline'
import craftingBags from './images/crafting-bags.webp?no-inline'
import craftingBagsSmall from './images/crafting-bags-320.webp?no-inline'
import gatheringMagic from './images/gathering-magic.webp?no-inline'
import dragonfallMap from './images/dragonfall-map.webp?no-inline'
import dragonfallMapSmall from './images/dragonfall-map-320.webp?no-inline'
import goldCrafting from './images/gold-crafting.webp?no-inline'
import goldCraftingSmall from './images/gold-crafting-640.webp?no-inline'
import goldCraftingMedium from './images/gold-crafting-960.webp?no-inline'
import runeList from './images/rune-list.webp?no-inline'
import runeListSmall from './images/rune-list-320.webp?no-inline'
import necroRuneCraft from './images/necro-rune-craft.webp?no-inline'
import necroRuneCraftSmall from './images/necro-rune-craft-640.webp?no-inline'
import necroRuneCraftMedium from './images/necro-rune-craft-960.webp?no-inline'
import teachMeSmall from './images/teach-me-small.webp?no-inline'
import teachMeSmallThumbnail from './images/teach-me-small-320.webp?no-inline'
import teachMeBig from './images/teach-me-big.webp?no-inline'
import teachMeBigSmall from './images/teach-me-big-640.webp?no-inline'
import teachMeBigMedium from './images/teach-me-big-1280.webp?no-inline'

export type GuideScreenshotId =
  | 'identify-gear' | 'loot-prices' | 'salvage-gear' | 'consume-luck'
  | 'gathering-glyphs' | 'trophy-shipments' | 'vault-objectives' | 'vault-rewards'
  | 'trophy-materials' | 'trophy-sale'
  | 'intermediate-crafting-materials' | 'crafting-bags'
  | 'gathering-magic' | 'dragonfall-map'
  | 'gold-crafting' | 'rune-list' | 'necro-rune-craft' | 'necro-rune-sell'
  | 'teach-me-small' | 'teach-me-big'
  | 'mount-keybinds' | 'mount-abilities' | 'combat-positioning'

interface GuideScreenshot {
  src: string
  srcset?: string
  width: number
  height: number
  alt: string
  caption: string
}

const necroRuneScreenshot: GuideScreenshot = {
  src: necroRuneCraft, srcset: `${necroRuneCraftSmall} 640w, ${necroRuneCraftMedium} 960w, ${necroRuneCraft} 1785w`,
  width: 1785, height: 1282,
  alt: 'The Superior Rune of the Necromancer recipe and its ingredients at a Tailoring Station.',
  caption: 'Crafting a Superior Rune of the Necromancer.'
}

// Owner-supplied landing-page images; game attribution is in the site footer.
// Unfilled positions render nothing while more screenshots are being prepared.
export const guideScreenshots: Partial<Record<GuideScreenshotId, GuideScreenshot>> = {
  'gold-crafting': {
    src: goldCrafting, srcset: `${goldCraftingSmall} 640w, ${goldCraftingMedium} 960w, ${goldCrafting} 1774w`,
    width: 1774, height: 887,
    alt: 'A painted pile of gold coins with crystals, crafting materials and tools.',
    caption: 'Gold and crafting materials.'
  },
  'rune-list': {
    src: runeList, srcset: `${runeListSmall} 320w, ${runeList} 498w`,
    width: 498, height: 1061,
    alt: 'A crafting-station list of runes and other upgrade components.',
    caption: 'Rune recipes at a crafting station.'
  },
  'necro-rune-craft': necroRuneScreenshot,
  // The supplied necroRuneSell.jpg currently contains the same image as necroRuneCraft.jpg.
  'necro-rune-sell': necroRuneScreenshot,
  'teach-me-small': {
    src: teachMeSmall, srcset: `${teachMeSmallThumbnail} 320w, ${teachMeSmall} 569w`,
    width: 569, height: 203,
    alt: 'Jeweler dialogue options to learn the discipline or see what can be crafted.',
    caption: 'Learning the jeweler discipline.'
  },
  'teach-me-big': {
    src: teachMeBig, srcset: `${teachMeBigSmall} 640w, ${teachMeBigMedium} 1280w, ${teachMeBig} 2488w`,
    width: 2488, height: 1802,
    alt: 'A character speaking to Isabel, the Master Jeweler, with the crafting-discipline dialogue open.',
    caption: 'Visiting the Master Jeweler.'
  },
  'identify-gear': {
    src: unidentifiedGear, width: 431, height: 393,
    alt: 'Unidentified gear in the GW2 inventory with Use All highlighted in its right-click menu.',
    caption: 'Unidentified gear → right-click → Use All.'
  },
  'salvage-gear': {
    src: salvageMenu, width: 519, height: 423,
    alt: 'A GW2 salvage kit menu with Salvage All Fine and Lower, Masterwork and Lower, and Rares.',
    caption: 'The kit’s right-click menu handles a whole rarity at once.'
  },
  'consume-luck': {
    src: consumeLuck, width: 597, height: 273,
    alt: 'Essence of Luck in a GW2 inventory, with Consume All in the right-click menu.',
    caption: 'Essence of Luck → right-click → Consume All.'
  },
  'intermediate-crafting-materials': {
    src: intermediateMaterials, srcset: `${intermediateMaterialsSmall} 320w, ${intermediateMaterialsMedium} 480w, ${intermediateMaterials} 1005w`,
    width: 1005, height: 822,
    alt: 'GW2 material storage showing intermediate crafting materials grouped by tier, with Tier 6 materials selected.',
    caption: 'Intermediate crafting materials, grouped by tier.'
  },
  'crafting-bags': {
    src: craftingBags, srcset: `${craftingBagsSmall} 320w, ${craftingBags} 885w`,
    width: 885, height: 399,
    alt: 'A Laurel Merchant listing Crafting Bags from Tiny to Heavy for one Laurel each.',
    caption: 'Crafting Bags at a Laurel Merchant: one Laurel per bag.'
  },
  'trophy-shipments': {
    src: trophyVendor, srcset: `${trophyVendorSmall} 480w, ${trophyVendor} 875w`,
    width: 875, height: 236,
    alt: 'Volatile Magic vendor showing four Trophy Shipments for 1,000 Volatile Magic and 4 gold.',
    caption: 'Four Trophy Shipments: 1,000 Volatile Magic + 4 gold.'
  },
  'trophy-materials': {
    src: trophyMaterials, srcset: `${trophyMaterialsSmall} 640w, ${trophyMaterialsMedium} 960w, ${trophyMaterials} 1536w`,
    width: 1536, height: 388,
    alt: 'Crafting trophies from four Trophy Shipments, with Vials of Powerful Blood being sold instantly on the Trading Post.',
    caption: 'The materials from this batch, with one of the Trading Post sales open.'
  },
  'gathering-magic': {
    src: gatheringMagic, width: 392, height: 560,
    alt: 'Harvested cabbage and Volatile Magic rewards in the loot feed.',
    caption: 'Cabbage and bonus Volatile Magic.'
  },
  'dragonfall-map': {
    src: dragonfallMap, srcset: `${dragonfallMapSmall} 320w, ${dragonfallMap} 567w`,
    width: 567, height: 261,
    alt: 'Dragonfall’s map label, showing level 80 and the Volatile Magic icon.',
    caption: 'Dragonfall.'
  },
  'trophy-sale': {
    src: trophySale, width: 469, height: 179,
    alt: 'GW2 Trading Post delivery box showing 8 gold, 76 silver and 10 copper in funds.',
    caption: 'The delivery box after selling the materials: 8g 76s 10c.'
  }
}
