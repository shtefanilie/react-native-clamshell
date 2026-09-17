import type { HybridObject } from 'react-native-nitro-modules'

export type Posture =
  | 'closed'
  | 'halfOpen'
  | 'flat'
  | 'fullyOpen'
  | 'unknown'
export type FoldOrientation = 'vertical' | 'horizontal' | 'none'
export type OcclusionType = 'none' | 'full'
export type CapabilityDetectionStatus = 'pending' | 'resolved'
export type ClamshellErrorCode =
  | 'sensorRegistrationFailed'
  | 'permissionDenied'

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

export interface AngleRange {
  min: number
  max: number
}

export interface FoldGeometry {
  bounds: Rect
  isSeparating: boolean
  occlusionType: OcclusionType
}

export interface FoldState {
  angle: number | null
  posture: Posture
  orientation: FoldOrientation
  geometry: FoldGeometry | null
}

export interface ClamshellCapabilities {
  detectionStatus: CapabilityDetectionStatus
  isFoldable: boolean
  hasContinuousAngle: boolean
  angleRange: AngleRange | null
  supportedPostures: Posture[]
  hasFoldGeometry: boolean
}

export interface ClamshellError {
  code: ClamshellErrorCode
  message: string
}

export interface Clamshell
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  getCapabilities(): ClamshellCapabilities
  addCapabilitiesListener(
    cb: (value: ClamshellCapabilities) => void
  ): () => void
  getSnapshot(): FoldState
  addStateListener(cb: (value: FoldState) => void): () => void
  startAngleUpdates(): void
  stopAngleUpdates(): void
  addAngleListener(cb: (degrees: number) => void): () => void
  addErrorListener(cb: (error: ClamshellError) => void): () => void
}
