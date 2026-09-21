export { Clamshell } from './facade'
export { useFoldState } from './hooks/useFoldState'
export { useHingeAngle } from './hooks/useHingeAngle'
export { useClamshellCapabilities } from './hooks/useClamshellCapabilities'
export type { Unsubscribe } from './internal/subscriptions'
export type {
  AngleRange,
  CapabilityDetectionStatus,
  ClamshellCapabilities,
  ClamshellError,
  ClamshellErrorCode,
  FoldGeometry,
  FoldOrientation,
  FoldState,
  OcclusionType,
  Posture,
  Rect,
} from './specs/Clamshell.nitro'
