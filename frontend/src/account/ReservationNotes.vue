<script setup lang="ts">
export interface ReservationNote { itemId: number; name: string | null; target: number }
defineProps<{ notes: readonly ReservationNote[]; source: string }>()
</script>

<template>
  <ul v-if="notes.length" class="reservation-notes" data-test="reservation-notes" aria-label="Saved reservations">
    <li v-for="note in notes" :key="note.itemId" class="reservation-notes__item" data-test="reservation-note">
      <strong>{{ note.name ?? `Item #${note.itemId}` }}</strong>:
      keep {{ note.target.toLocaleString('en-US') }} in {{ source }}
    </li>
  </ul>
</template>

<style scoped>
.reservation-notes {
  flex: 1 1 12rem;
  min-width: 0;
  box-sizing: border-box;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: var(--text-sm);
  overflow-wrap: anywhere;
}
.reservation-notes__item { margin-bottom: var(--space-2); }
</style>
