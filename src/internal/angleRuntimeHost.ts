import type { HybridObject } from 'react-native-nitro-modules'
import { NitroModules } from 'react-native-nitro-modules'
import {
  createSerializable,
  getUIRuntimeHolder,
  getUISchedulerHolder,
  type SerializableRef,
} from 'react-native-worklets'

export interface AngleRuntimeHost {
  addSink(worklet: (degrees: number) => void): number
  removeSink(subscriptionId: number): void
}

interface NativeAngleRuntimeHost
  extends HybridObject<{ ios: 'c++'; android: 'c++' }> {
  addSink(
    worklet: SerializableRef<(degrees: number) => void>,
    uiRuntimeHolder: object,
    uiSchedulerHolder: object
  ): number
  removeSink(subscriptionId: number): void
  emit(degrees: number): void
  startSynthetic(periodMilliseconds: number): void
  stopSynthetic(): void
  collectGarbage(): void
  invalidate(): void
}

export interface AngleRuntimeProbeHost extends AngleRuntimeHost {
  emit(degrees: number): void
  startSynthetic(periodMilliseconds: number): void
  stopSynthetic(): void
  collectGarbage(): void
  invalidate(): void
}

declare global {
  var __CLAMSHELL_ANGLE_RUNTIME_HOST__: AngleRuntimeProbeHost | undefined
}

export function getAngleRuntimeHost(): AngleRuntimeProbeHost {
  if (globalThis.__CLAMSHELL_ANGLE_RUNTIME_HOST__ == null) {
    const nativeHost =
      NitroModules.createHybridObject<NativeAngleRuntimeHost>('AngleRuntimeHost')
    globalThis.__CLAMSHELL_ANGLE_RUNTIME_HOST__ = {
      addSink(worklet) {
        return nativeHost.addSink(
          createSerializable(worklet, true),
          getUIRuntimeHolder(),
          getUISchedulerHolder()
        )
      },
      removeSink: id => nativeHost.removeSink(id),
      emit: degrees => nativeHost.emit(degrees),
      startSynthetic: period => nativeHost.startSynthetic(period),
      stopSynthetic: () => nativeHost.stopSynthetic(),
      collectGarbage: () => nativeHost.collectGarbage(),
      invalidate: () => nativeHost.invalidate(),
    }
  }
  return globalThis.__CLAMSHELL_ANGLE_RUNTIME_HOST__
}
