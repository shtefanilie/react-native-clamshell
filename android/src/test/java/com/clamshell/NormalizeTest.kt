package com.clamshell

import com.margelo.nitro.clamshell.FoldOrientation
import com.margelo.nitro.clamshell.OcclusionType
import com.margelo.nitro.clamshell.Posture
import com.margelo.nitro.clamshell.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NormalizeTest {
  @Test
  fun anglesUseAndroidsDocumentedInclusiveDegreeBounds() {
    for (degrees in listOf(0.0, 0.5, 90.0, 180.0, 270.0, 360.0)) {
      assertEquals(degrees, rawDegreesToCanonical(degrees), 0.0)
    }
    assertEquals(0.0, rawDegreesToCanonical(-15.0), 0.0)
    assertEquals(360.0, rawDegreesToCanonical(400.0), 0.0)
    for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertThrows(IllegalArgumentException::class.java) { rawDegreesToCanonical(invalid) }
    }
  }

  @Test
  fun postureMapsOnlyReachableAndroidStates() {
    assertEquals(Posture.HALFOPEN, featureToPosture(NativeFoldState.HALF_OPENED))
    assertEquals(Posture.FLAT, featureToPosture(NativeFoldState.FLAT))
    assertEquals(Posture.UNKNOWN, featureToPosture(NativeFoldState.UNKNOWN))
    assertEquals(Posture.UNKNOWN, featureToPosture(null))
    for (state in NativeFoldState.entries) {
      assertNotEquals(Posture.CLOSED, featureToPosture(state))
      assertNotEquals(Posture.FULLYOPEN, featureToPosture(state))
    }
  }

  @Test
  fun orientationDoesNotGuessFromAngleOrBounds() {
    assertEquals(FoldOrientation.VERTICAL, featureToOrientation(NativeFoldOrientation.VERTICAL))
    assertEquals(FoldOrientation.HORIZONTAL, featureToOrientation(NativeFoldOrientation.HORIZONTAL))
    assertEquals(FoldOrientation.NONE, featureToOrientation(NativeFoldOrientation.UNKNOWN))
    assertEquals(FoldOrientation.NONE, featureToOrientation(null))
  }

  @Test
  fun geometrySubtractsRootOriginBeforeConvertingToDip() {
    val bounds = FoldBoundsPx(20, 624, 60, 1824)
    val origin = RootOriginPx(20, 24)
    val geometry = featureToGeometry(bounds, origin, 2f, true, true)
    assertEquals(Rect(0.0, 300.0, 20.0, 600.0), geometry.bounds)
    assertEquals(true, geometry.isSeparating)
    assertEquals(OcclusionType.FULL, geometry.occlusionType)
    assertEquals(bounds, FoldBoundsPx(20, 624, 60, 1824))
    assertEquals(origin, RootOriginPx(20, 24))
  }

  @Test
  fun densityScalingAndOcclusionRemainIndependentOfSeparatingState() {
    for (density in listOf(1f, 2f, 3f, 2.75f)) {
      for (separating in listOf(false, true)) {
        for (occluding in listOf(false, true)) {
          val result = featureToGeometry(
            FoldBoundsPx(11, 22, 44, 110), RootOriginPx(0, 0),
            density, separating, occluding,
          )
          assertEquals(11.0 / density, result.bounds.x, 1e-10)
          assertEquals(22.0 / density, result.bounds.y, 1e-10)
          assertEquals(33.0 / density, result.bounds.width, 1e-10)
          assertEquals(88.0 / density, result.bounds.height, 1e-10)
          assertEquals(separating, result.isSeparating)
          assertEquals(if (occluding) OcclusionType.FULL else OcclusionType.NONE, result.occlusionType)
        }
      }
    }
  }

  @Test
  fun rotationUsesCurrentWindowCoordinatesAndPreservesZeroThicknessFolds() {
    val portrait = featureToGeometry(
      FoldBoundsPx(220, 12, 220, 412), RootOriginPx(20, 12), 2f, true, false,
    )
    val landscape = featureToGeometry(
      FoldBoundsPx(12, 220, 412, 220), RootOriginPx(12, 20), 2f, true, false,
    )
    assertEquals(Rect(100.0, 0.0, 0.0, 200.0), portrait.bounds)
    assertEquals(Rect(0.0, 100.0, 200.0, 0.0), landscape.bounds)
  }

  @Test
  fun splitScreenOriginsAndOffRootBoundsAreNotClipped() {
    val split = featureToGeometry(
      FoldBoundsPx(220, 300, 230, 900), RootOriginPx(200, 100), 2f, true, true,
    )
    val outside = featureToGeometry(
      FoldBoundsPx(20, 30, 80, 50), RootOriginPx(60, 40), 2f, false, false,
    )
    assertEquals(Rect(10.0, 100.0, 5.0, 300.0), split.bounds)
    assertEquals(Rect(-20.0, -5.0, 30.0, 10.0), outside.bounds)
  }

  @Test
  fun invalidGeometryThrowsInsteadOfManufacturingValidBounds() {
    for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
      assertThrows(IllegalArgumentException::class.java) {
        featureToGeometry(FoldBoundsPx(0, 0, 1, 1), RootOriginPx(0, 0), density, false, false)
      }
    }
    for (bounds in listOf(FoldBoundsPx(2, 0, 1, 1), FoldBoundsPx(0, 2, 1, 1))) {
      assertThrows(IllegalArgumentException::class.java) {
        featureToGeometry(bounds, RootOriginPx(0, 0), 1f, false, false)
      }
    }
  }

  @Test
  fun coordinateSubtractionCannotOverflowIntegerPixels() {
    val geometry = featureToGeometry(
      FoldBoundsPx(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
      RootOriginPx(Int.MAX_VALUE, Int.MAX_VALUE), 1f, false, false,
    )
    assertEquals(Rect(-4294967295.0, -4294967295.0, 4294967295.0, 4294967295.0), geometry.bounds)
  }
}
