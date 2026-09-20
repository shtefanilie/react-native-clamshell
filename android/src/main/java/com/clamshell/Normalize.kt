package com.clamshell

import com.margelo.nitro.clamshell.FoldGeometry
import com.margelo.nitro.clamshell.FoldOrientation
import com.margelo.nitro.clamshell.OcclusionType
import com.margelo.nitro.clamshell.Posture
import com.margelo.nitro.clamshell.Rect

internal enum class NativeFoldState { HALF_OPENED, FLAT, UNKNOWN }
internal enum class NativeFoldOrientation { VERTICAL, HORIZONTAL, UNKNOWN }

internal data class FoldBoundsPx(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class RootOriginPx(val x: Int, val y: Int)

internal fun rawDegreesToCanonical(degrees: Double): Double {
  require(degrees.isFinite()) { "Hinge angle must be finite; received $degrees" }
  // SensorEvent documents TYPE_HINGE_ANGLE as degrees in [0, 360], not [0, 180].
  return degrees.coerceIn(0.0, 360.0)
}

internal fun featureToPosture(state: NativeFoldState?): Posture = when (state) {
  NativeFoldState.HALF_OPENED -> Posture.HALFOPEN
  NativeFoldState.FLAT -> Posture.FLAT
  NativeFoldState.UNKNOWN, null -> Posture.UNKNOWN
}

internal fun featureToOrientation(orientation: NativeFoldOrientation?): FoldOrientation =
  when (orientation) {
    NativeFoldOrientation.VERTICAL -> FoldOrientation.VERTICAL
    NativeFoldOrientation.HORIZONTAL -> FoldOrientation.HORIZONTAL
    NativeFoldOrientation.UNKNOWN, null -> FoldOrientation.NONE
  }

internal fun featureToGeometry(
  boundsPx: FoldBoundsPx,
  rootOriginPx: RootOriginPx,
  density: Float,
  isSeparating: Boolean,
  isFullyOccluding: Boolean,
): FoldGeometry {
  require(density.isFinite() && density > 0f) {
    "Display density must be finite and positive; received $density"
  }
  require(boundsPx.right >= boundsPx.left && boundsPx.bottom >= boundsPx.top) {
    "Fold bounds must have nonnegative dimensions; received $boundsPx"
  }

  // Convert before subtraction to avoid Int overflow. Root origin already includes insets.
  val left = boundsPx.left.toDouble()
  val top = boundsPx.top.toDouble()
  val scale = density.toDouble()
  return FoldGeometry(
    bounds = Rect(
      x = (left - rootOriginPx.x.toDouble()) / scale,
      y = (top - rootOriginPx.y.toDouble()) / scale,
      width = (boundsPx.right.toDouble() - left) / scale,
      height = (boundsPx.bottom.toDouble() - top) / scale,
    ),
    isSeparating = isSeparating,
    occlusionType = if (isFullyOccluding) OcclusionType.FULL else OcclusionType.NONE,
  )
}
