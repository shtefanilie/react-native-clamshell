import { useEffect, useState } from 'react'
import { useSharedValue } from 'react-native-reanimated'
import type { SharedValue } from 'react-native-reanimated'
import { Clamshell, subscribeAngle } from '../facade'
import { getAngleRuntimeHost } from '../internal/angleRuntimeHost'

export function useHingeAngle(): SharedValue<number | null> {
  const [initialAngle] = useState(() => Clamshell.getSnapshot().angle)
  const angle = useSharedValue<number | null>(initialAngle)
  const enabled = useSharedValue(true)

  useEffect(
    () =>
      subscribeAngle(
        () => {
          angle.value = Clamshell.getSnapshot().angle
          const host = getAngleRuntimeHost()
          const id = host.addSink((degrees) => {
            'worklet'
            if (enabled.value) angle.value = degrees
          })
          return () => {
            enabled.value = false
            host.removeSink(id)
          }
        },
        (available) => {
          enabled.value = available
          if (!available) angle.value = null
        }
      ),
    [angle, enabled]
  )

  return angle
}
