<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import GuideScreenshot from './GuideScreenshot.vue'
import copperFedIcon from './images/copper-fed.webp?no-inline'
import runecraftersIcon from './images/runecrafters.webp?no-inline'
import silverFedIcon from './images/silver-fed.webp?no-inline'

const HABITS = [
  { id: 'loot', label: 'Unidentified gear & salvaging' },
  { id: 'gathering', label: 'Volatile Magic & Crafting Materials' },
  { id: 'vault', label: 'Wizard’s Vault' },
  { id: 'mounts', label: 'Mounts, shortcuts & keeping up' },
  { id: 'combat', label: 'Builds, meta events & more' }
] as const
type HabitId = typeof HABITS[number]['id']
const selected = ref<HabitId>('loot')
const currentIndex = computed(() => HABITS.findIndex(habit => habit.id === selected.value))
const readingRegion = ref<HTMLElement>()

async function advanceHabit(): Promise<void> {
  selected.value = HABITS[(currentIndex.value + 1) % HABITS.length]!.id
  await nextTick()
  readingRegion.value?.focus({ preventScroll: true })
  readingRegion.value?.scrollIntoView({ block: 'start' })
}
</script>

<template>
  <section class="beginner" aria-labelledby="beginner-heading">
    <header class="beginner__intro">
      <p class="beginner__eyebrow">Guild Wars 2 beginner tips · Gold & account progress</p>
      <h2 id="beginner-heading">Things I wish I knew sooner</h2>
      <p>Looking for ways to <strong>make more gold in Guild Wars 2</strong>?
      <br>Long time i felt poor in GW2. Saving for Ascended Gear took forever and affording Legendarys was unthinkable. Here is a few habits that started making a difference.
      <br>Not Farming, No grinding the same Map/ Event over and over. Just everyday i was flushing good gold down the drain without knowing. - Don`t be like me, be smarter! ;-)
      </p>
    </header>

    <nav class="beginner__chapters" aria-label="Beginner guide chapters">
      <button v-for="(habit, index) in HABITS" :key="habit.id" type="button"
        :aria-pressed="selected === habit.id" :aria-controls="'beginner-' + habit.id"
        @click="selected = habit.id">
        <span class="beginner__chapter-number" aria-hidden="true">0{{ index + 1 }}</span>
        <strong>{{ habit.label }}</strong>
      </button>
    </nav>
    <label class="beginner__picker">Explore the tips
      <select v-model="selected">
        <option v-for="(habit, index) in HABITS" :key="habit.id" :value="habit.id">{{ index + 1 }}. {{ habit.label }}</option>
      </select>
    </label>

    <div ref="readingRegion" class="beginner__reading" role="region"
      :aria-labelledby="'beginner-' + selected + '-title'" tabindex="-1">
      <article v-show="selected === 'loot'" id="beginner-loot" class="beginner__article" aria-labelledby="beginner-loot-title">
        <header class="beginner__article-heading">
          <h3 id="beginner-loot-title">Making gold from unidentified gear</h3>
        </header>

        <div class="beginner__gear-overview">
          <div class="beginner__gear-copy">
            <p>You don’t make gold from the gear directly! It’s what you can get out of it that helps your account progress.</p>
            <ol class="beginner__gear-steps">
              <li><strong>Identify first.</strong></li>
              <li><strong>Check for anything valuable.</strong></li>
              <li><strong>Then salvage.</strong></li>
            </ol>
            <ul class="beginner__gear-notes">
              <li>You can get Luck, Lucent Motes, Charms, Symbols and ectos.</li>
              <li>Identifying gives each piece a chance to turn into higher-rarity equipment, sometimes an exotic.</li>
              <li>Have a quick look at Trading Post prices before salvaging rare or exotic gear. My rule of thumb: if it’s worth less than an ecto, I salvage it and hope for at least one ecto.</li>
              <li>For very expensive drops, check whether retrieving the rune or sigil is worth it, assuming you have the right tool at hand.</li>
            </ul>
            <p class="beginner__side-note">If you’re lucky, a level 68+ rare or exotic item can give you up to three ectos.</p>
          </div>
          <div class="beginner__image-pair">
            <GuideScreenshot name="identify-gear" hide-caption />
            <GuideScreenshot name="salvage-gear" hide-caption />
          </div>
        </div>
        <section class="beginner__section">
          <h4>Pro tip: Right-click for bulk actions</h4>
          <div class="beginner__side-note">
            <p>Keep an eye on your bag space and use <strong>Alt + drag and drop</strong> to split stacks.</p>
            <p>Items in an <strong>Invisible Bag</strong> won’t be salvaged by this bulk action, so keep one for all the goodies you want to keep safe.</p>
          </div>
        </section>

        <section class="beginner__section">
          <h4>The salvage kits I recommend saving for</h4>
          <div class="beginner__kit-list">
            <div>
              <span>Blue gear & below</span>
              <div class="beginner__kit-name">
                <img :src="copperFedIcon" alt="" width="36" height="36" loading="lazy" decoding="async" />
                <strong>Copper-Fed</strong>
              </div>
              <p class="beginner__kit-cost">3 copper per use</p>
              <p>It saves a negligible amount of gold per use compared with basic kits. Its main value is inventory management and convenience.</p>
            </div>
            <div>
              <span>Green gear</span>
              <div class="beginner__kit-name">
                <img :src="runecraftersIcon" alt="" width="36" height="36" loading="lazy" decoding="async" />
                <strong>Runecrafter’s</strong>
              </div>
              <p class="beginner__kit-cost">30 copper per use</p>
              <p>Guarantees that slotted runes and sigils are salvaged into materials such as Lucent Motes, Charms and Symbols. It does not return the upgrades intact.</p>
              <p>On rare or exotic gear, the chance of receiving an ecto is slightly lower than with Silver-Fed.</p>
            </div>
            <div>
              <span>Yellow / rare gear</span>
              <div class="beginner__kit-name">
                <img :src="silverFedIcon" alt="" width="36" height="36" loading="lazy" decoding="async" />
                <strong>Silver-Fed</strong>
              </div>
              <p class="beginner__kit-cost">60 copper per use</p>
              <p>A higher cost per use, but a better chance of rare materials than Copper-Fed or Runecrafter’s. Consider it a convenience item: it costs significantly more per use than a Mystic Salvage Kit crafted with Mystic Forge Stones.</p>
            </div>
          </div>
          <p><strong>Those three kits in shared inventory slots</strong> are a convenience goal I’d recommend.</p>
          <p class="beginner__side-note">Regular kits work while saving up. A Master’s or Mystic Salvage Kit is a useful option for rares. Watch Gem Store sales and the gold-to-Gems rate before buying the unlimited ones.</p>
        </section>

        <section class="beginner__section beginner__illustrated">
          <div>
            <h4>Luck & Magic Find</h4>
            <p>Salvaging leaves you with Essence of Luck as well as materials. Consuming it builds account-wide Magic Find.</p>
            <p><strong>More Luck → higher Magic Find → better chances of rare drops.</strong> It’s an ongoing updraft!</p>
            <p class="beginner__side-note">Magic Find affects eligible drops from enemies, not the result of identifying gear.</p>
          </div>
          <GuideScreenshot name="consume-luck" />
        </section>
        <footer class="beginner__sources">
          <span>For the details on the GW2 Wiki</span>
          <a href="https://wiki.guildwars2.com/wiki/Unidentified_Gear" target="_blank" rel="noopener noreferrer">Unidentified gear ↗</a>
          <a href="https://wiki.guildwars2.com/wiki/Salvage_kit" target="_blank" rel="noopener noreferrer">Salvage kits ↗</a>
          <a href="https://wiki.guildwars2.com/wiki/Glob_of_Ectoplasm" target="_blank" rel="noopener noreferrer">Ectoplasm ↗</a>
          <a href="https://wiki.guildwars2.com/wiki/Magic_Find" target="_blank" rel="noopener noreferrer">Magic Find ↗</a>
        </footer>
      </article>
      <article v-show="selected === 'gathering'" id="beginner-gathering" class="beginner__article" aria-labelledby="beginner-gathering-title">
        <header class="beginner__article-heading">
          <h3 id="beginner-gathering-title">Volatile Magic & crafting materials — your side gig for gold</h3>
          <p>Some things are almost as good as gold!</p>
        </header>

        <div class="beginner__materials-overview">
          <div>
            <section>
              <h4>Intermediate crafting materials</h4>
              <p>Blood, bones, claws… they might look like bag clutter, but they’re ingredients for valuable crafts. Material storage groups them into tiers, from T1 to T6.</p>
              <p>Some tiers are worth a lot more than others. You either need the materials yourself, or you can fetch a nice price selling them on the Trading Post.</p>
            </section>

            <section class="beginner__section">
              <h4>But where do you get them?</h4>
              <p><strong>Crafting Bags and Trophy Shipments!</strong> Both turn currency you’ve collected into useful materials.</p>
              <ul class="beginner__material-options">
                <li><strong>Laurels → Crafting Bags.</strong> A Laurel Merchant sells bags for <strong>1 Laurel each</strong>. Choose the tier you need: Light Crafting Bags contain T3 materials, for example.</li>
                <li><strong>Volatile Magic → Trophy Shipments.</strong> Living World Season 4 vendors sell each shipment for <strong>250 Volatile Magic + 1 gold</strong>. Volatile Magic is another currency in your Wallet — more on getting it below.</li>
              </ul>
              <p class="beginner__side-note">I’d check Tier 3 bags as well as Tier 6. Compare the contents’ current prices on the Wiki or Trading Post before spending your Laurels; the highest tier isn’t automatically the best value.</p>
              <p class="beginner__main-point"><strong>Open the bags or shipments, keep what you need, and sell the rest.</strong> Useful for your own crafts, or gold toward your next upgrade.</p>
            </section>
          </div>
          <div class="beginner__image-pair">
            <GuideScreenshot name="intermediate-crafting-materials" hide-caption sizes="(max-width: 24rem) calc(100vw - 80px), 18rem" />
            <GuideScreenshot name="crafting-bags" hide-caption sizes="(max-width: 24rem) calc(100vw - 80px), 18rem" />
          </div>
        </div>

        <section class="beginner__section">
          <h4>Volatile Magic from gathering you’d do anyway</h4>
          <p>This was the bit I was missing: a <strong>Glyph of Volatility</strong> adds Volatile Magic to the ore, wood or plants I’m gathering anyway. I stop at nodes on my way across a map, and the extra currency builds up in the background.</p>
          <p><strong>Gather on the way → let the Magic build up → buy Trophy Shipments.</strong> It fits around whatever I was going to play that day. The trophies can go into my next craft or onto the Trading Post.</p>
          <p class="beginner__side-note">Season 4 events and gathering nodes also award Volatile Magic. You’ll need access to a Season 4 map with a shipment vendor to spend it this way.</p>
        </section>

        <section class="beginner__section">
          <h4>The next upgrade I’d save for</h4>
          <p>After the salvage kits, I’d save for <strong>a sickle, axe and pick with Glyphs of Volatility</strong>. Unlimited charges make gathering convenient; the glyphs provide the extra Magic.</p>
          <p>Watch Gem Store sales and check that the set includes <strong>three glyphs or glyph-selection containers</strong>. The <a href="https://wiki.guildwars2.com/wiki/Infinite_Volatile_Magic_Gathering_Tools" target="_blank" rel="noopener noreferrer">Infinite Volatile Magic Gathering Tools</a> are one option. I’d also watch the gold-to-Gems rate while saving up.</p>
          <GuideScreenshot name="gathering-glyphs" />
          <p class="beginner__side-note">Ordinary gathering tools work while you’re saving. A Glyph of Volatility or a Volatile gathering tool is what adds the bonus currency.</p>
        </section>
        <details class="beginner__details">
          <summary>Sharing a set between characters & choosing glyphs</summary>
          <div class="beginner__detail-body">
            <p>Gathering tools need to be equipped. Shared inventory slots or the bank make moving one set between characters easier. Unlike the salvage kits, a shared set isn’t automatically equipped on every character.</p>
            <p>Volatility is my pick for this routine. Watchknight adds sprockets, Bounty can add gathering strikes, and Industry speeds up gathering. Pick what suits the way you play.</p>
          </div>
        </details>

        <section class="beginner__section">
          <h4>Four shipments from my own bags</h4>
          <p>Here’s one batch: I spent <strong>1,000 Volatile Magic and 4 gold</strong>, opened the shipments, and sold the materials instantly on the Trading Post.</p>
          <dl class="beginner__example">
            <div><dt>Gold spent</dt><dd>4g</dd></div>
            <div><dt>Gold received after fees</dt><dd>8g 76s 10c</dd></div>
            <div><dt>Left after the purchase cost</dt><dd>4g 76s 10c</dd></div>
          </dl>
          <p class="beginner__side-note">That’s one batch at the prices in my screenshots. The 1,000 Volatile Magic is spent as well; different contents and market prices change the return.</p>
          <details class="beginner__details">
            <summary>See the screenshots for this batch</summary>
            <div class="beginner__detail-body">
              <GuideScreenshot name="trophy-shipments" />
              <GuideScreenshot name="trophy-materials" />
              <GuideScreenshot name="trophy-sale" />
            </div>
          </details>
        </section>
        <footer class="beginner__sources">
          <span>For the details on the GW2 Wiki</span>
          <a href="https://wiki.guildwars2.com/wiki/Laurel_Merchant" target="_blank" rel="noopener noreferrer">Laurel Merchants ↗</a>
          <a href="https://wiki.guildwars2.com/wiki/Light_Crafting_Bag" target="_blank" rel="noopener noreferrer">Tier 3 bag contents ↗</a>
          <a href="https://wiki.guildwars2.com/wiki/Trophy_Shipment" target="_blank" rel="noopener noreferrer">Trophy Shipments ↗</a>
          <a href="https://wiki.guildwars2.com/wiki/Volatile_Magic" target="_blank" rel="noopener noreferrer">Volatile Magic ↗</a>
          <a href="https://wiki.guildwars2.com/wiki/Glyph_of_Volatility" target="_blank" rel="noopener noreferrer">Gathering glyphs ↗</a>
        </footer>
      </article>

      <article v-show="selected === 'vault'" id="beginner-vault" class="beginner__article" aria-labelledby="beginner-vault-title">
        <header class="beginner__article-heading">
          <p class="beginner__eyebrow">Wizard’s Vault rewards</p>
          <h3 id="beginner-vault-title">Gold and useful upgrades from the Wizard’s Vault</h3>
          <p>The Wizard’s Vault has gold, useful gear and crafting materials behind objectives I often finish while playing anyway. Weeklies are where I’d start; I pick up dailies and Special objectives when they suit what I’m doing.</p>
        </header>
        <p class="beginner__main-point"><strong>Gold bags, Mystic Coins, useful gear and Laurels are the first things I look at.</strong> Astral Acclaim feels a lot more valuable once there’s something specific I’m saving it for.</p>
        <section class="beginner__section beginner__reward-list" aria-labelledby="beginner-reward-heading">
          <h4 id="beginner-reward-heading">What I’d spend my Acclaim on first</h4><dl>
            <div><dt>Season-limited gold bags</dt><dd>The straightforward option when I need spendable gold for the next upgrade.</dd></div>
            <div><dt>Mystic Coins</dt><dd>Worth checking even on a small account. I can sell them, or save them for crafting later.</dd></div>
            <div><dt>Ascended armor & weapons</dt><dd>A gear upgrade I can earn through the Vault instead of paying its crafting cost.</dd></div>
            <div><dt>Laurels</dt><dd>One Laurel buys a Heavy Crafting Bag with useful trophies. They can also go toward ascended amulets.</dd></div>
          </dl>
          <GuideScreenshot name="vault-rewards" />
        </section>
        <aside class="beginner__side-note"><strong>A useful limit to know:</strong> at 1,300 Astral Acclaim, objective rewards stop being claimable until some is spent. I also check the season timer before the limited shop stock refreshes.</aside>

        <section class="beginner__section">
          <h4>Weeklies full of a game mode you don’t play?</h4>
          <p>The cog in <strong>Wizard’s Vault → Daily or Weekly</strong> lets you choose PvE, PvP and WvW separately for each category. Selecting the modes I actually play makes the whole thing much less of a chore.</p>
          <GuideScreenshot name="vault-objectives" />
          <p class="beginner__side-note">After claiming an objective, a mode change waits for that category’s next reset. The daily login objective is an exception.</p>
        </section>
        <details class="beginner__details"><summary>A few other rewards I’d keep an eye on</summary><div class="beginner__detail-body">
          <p><strong>Legendary Weapon Starter Kit / Key:</strong> a precursor and major gifts toward a selected legendary. There’s still a project to finish, but it can be a very worthwhile head start. Availability depends on your expansions.</p>
          <p><strong>Mystic Clovers</strong> are useful for legendary crafting; <strong>Lucent Crystal Vault Bags</strong> provide crafting materials; a <strong>Bag Slot Expansion</strong> means fewer full-bag interruptions.</p>
          <p><strong>Black Lion Mastery Coffers</strong> give mastery XP, not Mastery Points. I still need exploration and achievements for the points.</p>
        </div></details>
        <details class="beginner__details"><summary>Laurels, ascended amulets & a Magic Find extra</summary><div class="beginner__detail-body">
          <p>A Heavy Crafting Bag costs <strong>1 Laurel</strong>; its contents’ gold value changes with the market. This is different from the Trophy Shipment bought with Volatile Magic.</p>
          <p>Laurel merchants also have ascended amulets for <strong>30 Laurels</strong>. With suitable stats chosen, a <strong>Magical Enrichment</strong> is a possible extra: <strong>20 Laurels</strong> for <strong>+20% Magic Find</strong> in the amulet’s enrichment slot.</p>
        </div></details>
        <details class="beginner__details"><summary>What waits until I have Acclaim to spare</summary><div class="beginner__detail-body">
          <p>When I’m saving for gold or gear, skins, Build Storage and Build Template Expansions, Transmutation Charges and Lesser Essence of Gold wait. Black Lion Salvage Kits, Revive Orbs, Tomes of Knowledge, Vision Crystals and Upgrade Extractors are purchases I make when I have a use for them.</p>
          <p>After the seasonal rewards I want, unlimited Wizard’s Boxes of Crafting Materials or Legendary Essences of Luck can use up excess Acclaim. Luck builds Magic Find rather than providing gold to spend.</p>
        </div></details>
        <footer class="beginner__sources"><span>For the details on the GW2 Wiki</span><a href="https://wiki.guildwars2.com/wiki/Wizard%27s_Vault" target="_blank" rel="noopener noreferrer">Wizard’s Vault & rewards ↗</a><a href="https://wiki.guildwars2.com/wiki/Laurel_Merchant" target="_blank" rel="noopener noreferrer">Laurel merchants ↗</a><a href="https://wiki.guildwars2.com/wiki/Magical_Enrichment" target="_blank" rel="noopener noreferrer">Magical Enrichment ↗</a></footer>
      </article>

      <article v-show="selected === 'mounts'" id="beginner-mounts" class="beginner__article" aria-labelledby="beginner-mounts-title">
        <header class="beginner__article-heading">
          <p class="beginner__eyebrow">GW2 mounts & exploration</p>
          <h3 id="beginner-mounts-title">Keeping up gets a lot more fun once the mounts click</h3>
          <p>Ever arrived at an event just as everyone else was leaving? On busy maps, getting around can feel like a game of its own. Mounts made a huge difference to how much of Tyria I could comfortably join in with.</p>
        </header>
        <p class="beginner__main-point"><strong>Separate mount keybinds were the change I wish I’d made earlier.</strong> Swapping to the right mount for a slope, a stretch of water or a high ledge feels so much better than opening the selection menu each time.</p>
        <section class="beginner__section">
          <h4>A small settings change with a big payoff</h4>
          <p>The bindings are under <strong>Options → Control Options → Mounts</strong>. I’d give the mounts I use most their own key combinations, then practice switching while exploring.</p>
          <GuideScreenshot name="mount-keybinds" />
          <p>For the next unlock, the <a href="https://wiki.guildwars2.com/wiki/Mount" target="_blank" rel="noopener noreferrer">GW2 Wiki’s mount overview</a> helps with the collections, costs and expansion requirements. Unlocks are account-wide, so the effort helps more than one character.</p>
        </section>
        <details class="beginner__details"><summary>What I use the different mounts for</summary><div class="beginner__detail-body beginner__reward-list"><dl>
          <div><dt>Jackal</dt><dd>Blinking across awkward ground and uphill. Its mastery also gives access to Sand Portals.</dd></div>
          <div><dt>Warclaw</dt><dd>Getting moving again while in combat in PvE. WvW has different mounting rules.</dd></div>
          <div><dt>Griffon</dt><dd>Fast flight with some starting height and a bit of practice diving and pulling up.</dd></div>
          <div><dt>Skyscale</dt><dd>Hovering and reaching a particular ledge. Masteries add useful abilities such as fireballs.</dd></div>
          <div><dt>Skimmer</dt><dd>Crossing water, and travelling underwater once the additional mastery is unlocked.</dd></div>
          <div><dt>Springer</dt><dd>Getting height, especially before having the flying mounts.</dd></div>
          <div><dt>Raptor</dt><dd>Everyday ground travel and jumping gaps.</dd></div>
          <div><dt>Roller Beetle</dt><dd>Open ground with room to build up speed.</dd></div>
        </dl><GuideScreenshot name="mount-abilities" /></div></details>
        <aside class="beginner__side-note"><strong>Masteries are part of the upgrade.</strong> Mounts help reach Mastery Points; spending those points unlocks more abilities. Mastery tracks need XP as well. Vault Mastery Coffers can help with XP, while the points come from playing.</aside>
        <p class="beginner__closing">I still find the rush exhausting sometimes. Treating it as a bit of a sport — learning routes and getting better at the movement — made keeping up much more enjoyable.</p>
        <footer class="beginner__sources"><span>For the details on the GW2 Wiki</span><a href="https://wiki.guildwars2.com/wiki/Mount" target="_blank" rel="noopener noreferrer">Mount unlocks ↗</a><a href="https://wiki.guildwars2.com/wiki/Options#Control_Options" target="_blank" rel="noopener noreferrer">Mount keybinds ↗</a><a href="https://wiki.guildwars2.com/wiki/Warclaw" target="_blank" rel="noopener noreferrer">Warclaw ↗</a></footer>
      </article>

      <article v-show="selected === 'combat'" id="beginner-combat" class="beginner__article" aria-labelledby="beginner-combat-title">
        <header class="beginner__article-heading">
          <p class="beginner__eyebrow">Meta events, builds & WvW</p>
          <h3 id="beginner-combat-title">More time in the fight makes the whole game better</h3>
          <p>When everyone else seems to be cruising through an event and I’m on the floor again, it’s easy to think I just need more damage. What helped first was understanding where to stand, what to dodge and what the encounter was asking me to do.</p>
        </header>
        <p class="beginner__main-point"><strong>I’d work on staying in the fight first, then on hitting harder.</strong> Time spent alive is time actually playing, learning the encounter and helping the group.</p>
        <section class="beginner__section">
          <h4>A build and a little context can change a fight</h4>
          <p>A veteran’s setup might have matching gear, practiced skills and years of encounter knowledge behind it. Looking up a build that fits my character, or reading an unfamiliar story fight or meta event on the <a href="https://wiki.guildwars2.com/wiki/Main_Page" target="_blank" rel="noopener noreferrer">GW2 Wiki</a>, gives me something useful to try next.</p>
          <p>A friendly guild helps here, too. Someone showing me the mechanic once can save a lot of wondering what just happened.</p>
          <GuideScreenshot name="combat-positioning" />
        </section>
        <section class="beginner__section">
          <h4>About those rescue attempts…</h4>
          <p>I’m all for reviving people. I just try to look at what downed them before joining them in the same attack. Sometimes finishing the enemy first is the better rescue; sometimes a defeated player can return from a nearby waypoint.</p>
        </section>
        <aside class="beginner__side-note"><strong>The WvW version I keep coming back to:</strong> stay on the commander’s tag, follow the group’s movement and try to stay alive. There’s a lot to learn before the damage numbers become the interesting part.</aside>
        <p class="beginner__closing">One shortcut I use all the time: <code>/wiki</code> followed by a name in game opens a search for that item, event or encounter.</p>
        <footer class="beginner__sources"><span>For the details on the GW2 Wiki</span><a href="https://wiki.guildwars2.com/wiki/Downed" target="_blank" rel="noopener noreferrer">Downed state ↗</a><a href="https://wiki.guildwars2.com/wiki/World_versus_World" target="_blank" rel="noopener noreferrer">World vs. World ↗</a><a href="https://wiki.guildwars2.com/wiki/Chat_command" target="_blank" rel="noopener noreferrer">Chat & wiki commands ↗</a></footer>
      </article>
    </div>

    <footer class="beginner__next"><span>{{ currentIndex + 1 }} of {{ HABITS.length }}</span><button type="button" @click="advanceHabit"><template v-if="currentIndex < HABITS.length - 1">Next: {{ HABITS[currentIndex + 1]!.label }} <span aria-hidden="true">→</span></template><template v-else>Back to gold from loot <span aria-hidden="true">↑</span></template></button></footer>
  </section>
</template>

<style scoped>
.beginner { display: grid; gap: var(--space-5); }
.beginner__intro { display: grid; gap: var(--space-3); }
.beginner__intro > p { max-width: 86ch; line-height: 1.7; }
.beginner__intro h2 { font-size: var(--text-2xl); }
.beginner__chapters { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: var(--space-2); }
.beginner__chapters button { display: flex; align-items: flex-start; gap: var(--space-3); padding: var(--space-4) var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius-control); background: transparent; color: var(--color-text); font: inherit; text-align: left; }
.beginner__chapters button:hover { background: var(--color-surface); }
.beginner__chapters button[aria-pressed="true"] { background: var(--color-raised); border-color: var(--color-highlight); box-shadow: inset 0 2px var(--color-highlight); }
.beginner__chapter-number { color: var(--color-highlight); font-size: var(--text-sm); line-height: 1.8; }
.beginner__picker { display: none; }
.beginner__reading { min-width: 0; border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: var(--color-surface); padding: var(--space-6); }
.beginner__article { max-width: 94ch; margin-inline: auto; }
.beginner__article-heading { display: grid; gap: var(--space-3); margin-bottom: var(--space-5); }
.beginner__article-heading h3 { color: var(--color-highlight); font-size: var(--text-xl); text-wrap: balance; }
.beginner__article h4 { color: var(--color-highlight); margin: 0 0 var(--space-3); font-size: var(--text-lg); line-height: 1.4; }
.beginner__article p { line-height: 1.7; }
.beginner__article p + p { margin-top: var(--space-3); }
.beginner__eyebrow { color: var(--color-highlight); font-size: var(--text-sm); font-weight: 600; }
.beginner__main-point { padding-left: var(--space-4); border-left: 3px solid var(--color-highlight); font-size: var(--text-lg); }
.beginner__gear-notes { display: grid; gap: var(--space-2); margin: 0; padding-left: var(--space-5); line-height: 1.7; }
.beginner__gear-steps { display: grid; gap: var(--space-2); margin-block: var(--space-5); padding-left: var(--space-6); border-left: 3px solid var(--color-highlight); font-size: var(--text-lg); line-height: 1.5; }
.beginner__gear-steps li { padding-left: var(--space-1); }
.beginner__gear-steps li::marker { color: var(--color-highlight); font-weight: 600; }
.beginner__section { margin-top: var(--space-6); }
.beginner__image-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-5); align-items: start; }
.beginner__gear-overview, .beginner__materials-overview { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 18rem); gap: var(--space-6); align-items: start; }
.beginner__gear-overview .beginner__image-pair, .beginner__materials-overview .beginner__image-pair { grid-template-columns: minmax(0, 1fr); gap: var(--space-4); }
.beginner__gear-overview .guide-screenshot, .beginner__materials-overview .guide-screenshot { margin: 0; }
.beginner__material-options { display: grid; gap: var(--space-3); margin-block: var(--space-4); padding-left: var(--space-5); line-height: 1.7; }
.beginner__kit-list { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--space-4); margin-block: var(--space-4); }
.beginner__kit-list > div { border-top: 1px solid var(--color-border); padding-top: var(--space-3); }
.beginner__kit-list span { display: block; margin-bottom: var(--space-2); color: var(--color-muted); font-size: var(--text-sm); }
.beginner__kit-list strong { font-size: var(--text-lg); }
.beginner__kit-name { display: flex; align-items: center; gap: var(--space-3); }
.beginner__kit-name img { width: 36px; height: 36px; flex-shrink: 0; }
.beginner__kit-list p { color: var(--color-muted); font-size: var(--text-sm); }
.beginner__kit-list .beginner__kit-cost { margin-top: var(--space-3); color: var(--color-text); font-weight: 600; }
.beginner__illustrated { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 20rem); gap: var(--space-5); align-items: center; }
.beginner__side-note { margin-top: var(--space-4); padding-left: var(--space-4); border-left: 1px solid var(--color-border); color: var(--color-muted); font-size: var(--text-sm); line-height: 1.65; }
.beginner__details { margin-top: var(--space-4); border-bottom: 1px solid var(--color-border); }
.beginner__details summary { cursor: pointer; padding-block: var(--space-4); font-weight: 600; }
.beginner__details summary:hover { color: var(--color-secondary); }
.beginner__detail-body { display: grid; gap: var(--space-4); padding: 0 0 var(--space-5); }
.beginner__detail-body p + p { margin-top: 0; }
.beginner__reward-list dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-4) var(--space-5); margin: var(--space-4) 0 var(--space-5); }
.beginner__reward-list dl > div { border-top: 1px solid var(--color-border); padding-top: var(--space-3); }
.beginner__reward-list dt { font-weight: 600; margin-bottom: var(--space-2); }
.beginner__reward-list dd { margin: 0; color: var(--color-muted); line-height: 1.65; }
.beginner__example { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--space-4); margin: var(--space-5) 0; padding-block: var(--space-4); border-block: 1px solid var(--color-border); }
.beginner__example dt { color: var(--color-muted); font-size: var(--text-sm); margin-bottom: var(--space-2); }
.beginner__example dd { margin: 0; font-size: var(--text-xl); font-weight: 600; }
.beginner__closing { margin-top: var(--space-5); }
.beginner__sources { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--space-2) var(--space-4); margin-top: var(--space-5); padding-top: var(--space-4); border-top: 1px solid var(--color-border); font-size: var(--text-sm); }
.beginner__sources > span { flex-basis: 100%; color: var(--color-muted); }
.beginner__next { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: var(--space-3); }
.beginner__next > span { color: var(--color-muted); font-size: var(--text-sm); }
.beginner__next button { min-height: 44px; padding: var(--space-3) var(--space-4); border: 1px solid var(--color-border); border-radius: var(--radius-control); background: transparent; color: var(--color-secondary); font: inherit; }
.beginner__next button:hover { background: var(--color-raised); }
@media (max-width: 60rem) {
  .beginner__chapters { display: none; }
  .beginner__picker { display: grid; gap: var(--space-2); max-width: 30rem; font-weight: 600; }
  .beginner__picker select { width: 100%; min-height: 44px; padding: var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-surface); color: var(--color-text); font: inherit; }
}
@media (max-width: 48rem) {
  .beginner__reading { padding: var(--space-5) var(--space-4); }
  .beginner__gear-overview, .beginner__materials-overview { grid-template-columns: minmax(0, 1fr); }
  .beginner__gear-overview .beginner__image-pair, .beginner__materials-overview .beginner__image-pair { width: 100%; max-width: 18rem; justify-self: center; }
  .beginner__image-pair, .beginner__illustrated, .beginner__reward-list dl { grid-template-columns: minmax(0, 1fr); }
  .beginner__example, .beginner__kit-list { grid-template-columns: minmax(0, 1fr); }
  .beginner__example { gap: var(--space-3); }
  .beginner__example > div { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: var(--space-2); }
  .beginner__example dd { font-size: var(--text-lg); }
}
</style>
