<script setup lang="ts">
import { INFO_DESTINATIONS, type DestinationId } from './destinations'

defineProps<{ currentId: DestinationId }>()
defineEmits<{ navigate: [id: DestinationId] }>()

/** A plain click navigates in place; a modified click keeps its normal browser meaning. */
function onClick(event: MouseEvent, id: DestinationId, emit: (id: DestinationId) => void): void {
  if (event.defaultPrevented || event.button !== 0) return
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  emit(id)
}
</script>

<template>
  <footer class="site-footer" data-test="site-footer">
    <nav class="site-footer__nav" aria-label="Legal and privacy">
      <ul class="site-footer__list">
        <li v-for="page in INFO_DESTINATIONS" :key="page.id">
          <a
            class="site-footer__link"
            :href="page.path"
            :aria-current="page.id === currentId ? 'page' : undefined"
            :data-test="`footer-link-${page.id}`"
            @click="onClick($event, page.id, id => $emit('navigate', id))"
          >{{ page.label }}</a>
        </li>
      </ul>
    </nav>
    <p class="site-footer__attribution">
      © ArenaNet LLC. All rights reserved. NCSOFT, ArenaNet, Guild Wars, Guild Wars 2, GW2,
      Heart of Thorns, Path of Fire, End of Dragons, Secrets of the Obscure, Janthir Wilds,
      Visions of Eternity, and all associated logos, designs, and composite marks are trademarks
      or registered trademarks of NCSOFT Corporation. All other trademarks are the property of
      their respective owners.
    </p>
  </footer>
</template>
