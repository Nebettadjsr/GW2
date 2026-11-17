<script setup lang="ts">
import { watch } from 'vue'
import AccountWorkspace from './AccountWorkspace.vue'
import { connection, restoreConnection } from './api/accountSession'
import { isPublicDestination } from './shell/destinations'
import { useDesktopTools } from './shell/useDesktopTools'
import { useHashRoute } from './shell/useHashRoute'

/**
 * The application root. A key retained in this browser session is proven again before any account
 * data loads; the workspace below is re-created whenever the connected account may have changed, so
 * nothing of a previous account survives into the next (`ARCHITECTURE.md`).
 */
const route = useHashRoute()
const desktop = useDesktopTools()

/**
 * Opening Home or a public information page issues no account request, even with a retained key: restoring
 * is deferred until a real application destination is open. A session that is already connected is
 * left alone, and `restoreConnection` is a no-op unless there is a retained, unproven key.
 */
watch(
  () => isPublicDestination(route.current.value) || !desktop.value,
  (withoutAccountWork) => {
    if (!withoutAccountWork) void restoreConnection()
  },
  { immediate: true }
)
</script>

<template>
  <AccountWorkspace :key="connection.generation" />
</template>
