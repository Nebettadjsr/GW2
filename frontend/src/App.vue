<script setup lang="ts">
import { onMounted } from 'vue'
import AccountWorkspace from './AccountWorkspace.vue'
import { connection, restoreConnection } from './api/accountSession'

/**
 * The application root. A key retained in this browser session is proven again before any account
 * data loads; the workspace below is re-created whenever the connected account may have changed, so
 * nothing of a previous account survives into the next (`TARGET_ARCHITECTURE.md` 8.2).
 */
onMounted(() => {
  void restoreConnection()
})
</script>

<template>
  <AccountWorkspace :key="connection.generation" />
</template>
