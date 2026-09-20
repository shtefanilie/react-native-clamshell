import { useSyncExternalStore } from 'react'
import type { ClamshellCapabilities } from '../specs/Clamshell.nitro'
import { capabilitiesStore } from '../stores'

export function useClamshellCapabilities(): ClamshellCapabilities {
  return useSyncExternalStore(
    capabilitiesStore.subscribe,
    capabilitiesStore.getSnapshot,
    capabilitiesStore.getSnapshot
  )
}
