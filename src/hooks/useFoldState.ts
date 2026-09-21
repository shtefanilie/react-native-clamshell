import { useSyncExternalStore } from 'react'
import type { FoldState } from '../specs/Clamshell.nitro'
import { foldStateStore } from '../stores'

export function useFoldState(): FoldState {
  return useSyncExternalStore(
    foldStateStore.subscribe,
    foldStateStore.getSnapshot,
    foldStateStore.getSnapshot
  )
}
