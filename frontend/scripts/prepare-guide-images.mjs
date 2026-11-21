import { mkdir, stat } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'

const encoder = process.argv[2] ?? 'cwebp'
const frontend = fileURLToPath(new URL('../', import.meta.url))
const sourceDirectory = join(frontend, 'screenshots')
const outputDirectory = join(frontend, 'src', 'home', 'images')
const images = [
  { source: 'rightClickGear.jpg', name: 'unidentified-gear', widths: [431] },
  { source: 'rightClickTool.jpg', name: 'salvage-menu', widths: [519] },
  { source: 'consumeAllLuck.jpg', name: 'consume-luck', widths: [597] },
  { source: 'buyTrophy.jpg', name: 'trophy-vendor', crop: [160, 978, 875, 236], widths: [480, 875] },
  { source: 'example4TrophyShipment.jpg', name: 'trophy-materials', widths: [640, 960, 1536] },
  { source: 'example4TrothpySellProfitInstantSell.jpg', name: 'trophy-sale', widths: [469] },
  { source: 'intermCraftingMaterials.jpg', name: 'intermediate-crafting-materials', widths: [320, 480, 1005] },
  { source: 'craftingBags.jpg', name: 'crafting-bags', widths: [320, 885] },
  { source: 'gatheringMagicExample.jpg', name: 'gathering-magic', widths: [392] },
  { source: 'DragonfallMap.jpg', name: 'dragonfall-map', widths: [320, 567] }
]

await mkdir(outputDirectory, { recursive: true })
let originalBytes = 0
let outputBytes = 0
for (const image of images) {
  const source = join(sourceDirectory, image.source)
  originalBytes += (await stat(source)).size
  for (const width of image.widths) {
    const suffix = width === image.widths.at(-1) ? '' : `-${width}`
    const output = join(outputDirectory, `${image.name}${suffix}.webp`)
    const crop = image.crop ? ['-crop', ...image.crop.map(String)] : []
    const converted = spawnSync(encoder, ['-quiet', '-q', '86', '-m', '6', '-sharp_yuv',
      '-metadata', 'none', ...crop, '-resize', String(width), '0', source, '-o', output], { encoding: 'utf8' })
    if (converted.error) throw converted.error
    if (converted.status !== 0) throw new Error(converted.stderr || `cwebp failed for ${image.source}`)
    const bytes = (await stat(output)).size
    outputBytes += bytes
    console.log(`${image.name}${suffix}.webp: ${bytes} bytes`)
  }
}
console.log(JSON.stringify({ originalBytes, outputBytes, savedPercent: Math.round((1 - outputBytes / originalBytes) * 100) }))
