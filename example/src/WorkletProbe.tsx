import React, { useEffect } from 'react'
import { View } from 'react-native'
import { useSharedValue } from 'react-native-reanimated'
import { isUIRuntime } from 'react-native-worklets'
import { getAngleRuntimeHost } from '../../src/internal/angleRuntimeHost'

export function WorkletProbe() {
  const sample = useSharedValue({ degrees: 0, isUIRuntime: false })

  useEffect(() => {
    const host = getAngleRuntimeHost()
    const subscriptionId = host.addSink(degrees => {
      'worklet'
      sample.value = { degrees, isUIRuntime: isUIRuntime() }
    })

    return () => host.removeSink(subscriptionId)
  }, [sample])

  return <View testID="worklet-probe" />
}
