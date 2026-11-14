<script setup lang="ts">
import { watch } from 'vue'
import AccountWorkspace from './AccountWorkspace.vue'
import { connection, restoreConnection } from './api/accountSession'
import { isInfoDestination } from './shell/destinations'
import { useHashRoute } from './shell/useHashRoute'

/**
 * The application root. A key retained in this browser session is proven again before any account
 * data loads; the workspace below is re-created whenever the connected account may have changed, so
 * nothing of a previous account survives into the next (`TARGET_ARCHITECTURE.md` 8.2).
 */
const route = useHashRoute()

/**
 * Opening a public information page issues no account request, even with a retained key: restoring
 * is deferred until a real application destination is open. A session that is already connected is
 * left alone, and `restoreConnection` is a no-op unless there is a retained, unproven key.
 */
watch(
  () => isInfoDestination(route.current.value),
  (onInfoPage) => {
    if (!onInfoPage) void restoreConnection()
  },
  { immediate: true }
)
</script>

<template>
  <AccountWorkspace :key="connection.generation" />
</template>
