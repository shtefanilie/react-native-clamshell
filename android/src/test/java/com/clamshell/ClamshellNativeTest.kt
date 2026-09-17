package com.clamshell

import com.margelo.nitro.clamshell.CapabilityDetectionStatus
import com.margelo.nitro.clamshell.ClamshellCapabilities
import com.margelo.nitro.clamshell.ClamshellError
import com.margelo.nitro.clamshell.FoldOrientation
import com.margelo.nitro.clamshell.FoldState
import com.margelo.nitro.clamshell.HybridClamshellSpec
import com.margelo.nitro.clamshell.Posture
import org.junit.Assert.assertEquals
import org.junit.Test

private class ClamshellContractCompileFixture : HybridClamshellSpec() {
  override fun getCapabilities() = ClamshellCapabilities(
    detectionStatus = CapabilityDetectionStatus.PENDING,
    isFoldable = false,
    hasContinuousAngle = false,
    angleRange = null,
    supportedPostures = emptyArray(),
    hasFoldGeometry = false,
  )

  override fun addCapabilitiesListener(cb: (ClamshellCapabilities) -> Unit): () -> Unit = {}

  override fun getSnapshot() = FoldState(
    angle = null,
    posture = Posture.UNKNOWN,
    orientation = FoldOrientation.NONE,
    geometry = null,
  )

  override fun addStateListener(cb: (FoldState) -> Unit): () -> Unit = {}

  override fun startAngleUpdates() = Unit

  override fun stopAngleUpdates() = Unit

  override fun addAngleListener(cb: (Double) -> Unit): () -> Unit = {}

  override fun addErrorListener(cb: (ClamshellError) -> Unit): () -> Unit = {}
}

class ClamshellNativeTest {
  @Test
  fun scaffoldLoads() {
    assertEquals("com.clamshell", HybridClamshell::class.java.packageName)
  }
}
