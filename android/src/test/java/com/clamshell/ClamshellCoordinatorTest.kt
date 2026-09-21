package com.clamshell

import com.margelo.nitro.clamshell.CapabilityDetectionStatus
import com.margelo.nitro.clamshell.ClamshellErrorCode
import com.margelo.nitro.clamshell.FoldState
import com.margelo.nitro.clamshell.Posture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClamshellCoordinatorTest {
  private class Sensor : AngleSensorDriver {
    val callbacks = CopyOnWriteArrayList<(Double) -> Unit>()
    var registers = 0
    var unregisters = 0
    var succeeds = true
    var failure: RuntimeException? = null
    var initialAngle: Double? = null
    var onRegister: (() -> Unit)? = null
    var onUnregister: (() -> Unit)? = null

    override fun register(onAngle: (Double) -> Unit): Boolean {
      registers++
      callbacks.add(onAngle)
      onRegister?.invoke()
      failure?.let { throw it }
      initialAngle?.let(onAngle)
      return succeeds
    }

    override fun unregister() {
      unregisters++
      onUnregister?.invoke()
    }
    fun emit(angle: Double) { callbacks.last()(angle) }
  }

  private class Fixture {
    val uiAngles = CopyOnWriteArrayList<Double>()
    val diagnostics = CopyOnWriteArrayList<String>()
    var invalidations = 0
    val coordinator = ClamshellCoordinator(
      emitUiAngle = { uiAngles.add(it) },
      invalidateUiSinks = { invalidations++ },
      reportDiagnostic = { message, _ -> diagnostics.add(message) },
    )
    val sensor = Sensor()

    fun activate(): Long {
      coordinator.setSensor(sensor, 360.0, featureFlag = true)
      val host = coordinator.attachHost()
      coordinator.setForeground(true)
      return host
    }
  }

  private fun feature(state: NativeFoldState = NativeFoldState.HALF_OPENED) =
    FoldFeatureSample(
      state, NativeFoldOrientation.VERTICAL,
      FoldBoundsPx(20, 624, 60, 1824), RootOriginPx(20, 24), 2f,
      isSeparating = true, isFullyOccluding = true,
    )

  @Test
  fun readinessUsesIndependentSensorAndLayoutEvidence() {
    val f = Fixture()
    val c = f.coordinator
    assertEquals(CapabilityDetectionStatus.PENDING, c.getCapabilities().detectionStatus)
    assertNull(c.getSnapshot().angle)
    val host = c.attachHost()
    c.onLayout(host, null)
    assertEquals(CapabilityDetectionStatus.RESOLVED, c.getCapabilities().detectionStatus)
    assertFalse(c.getCapabilities().isFoldable)
    c.onLayout(host, feature())
    assertTrue(c.getCapabilities().isFoldable)
    assertTrue(c.getCapabilities().hasFoldGeometry)
    assertFalse(c.getCapabilities().hasContinuousAngle)
    c.setSensor(f.sensor, 360.0, featureFlag = false)
    c.onLayout(host, null)
    assertTrue(c.getCapabilities().isFoldable)
    assertTrue(c.getCapabilities().hasContinuousAngle)
    assertFalse(c.getCapabilities().hasFoldGeometry)
    assertEquals(360.0, c.getCapabilities().angleRange!!.asSecondOrNull()!!.max, 0.0)
    assertTrue(f.diagnostics.any { it.contains("feature flag") })
    c.dispose()
  }

  @Test
  fun angleCacheDoesNotEmitStateAndNextPostureCarriesLatestAngle() {
    val f = Fixture()
    val host = f.activate()
    val states = mutableListOf<FoldState>()
    f.coordinator.addStateListener { states.add(it) }
    f.coordinator.startAngleUpdates()
    f.sensor.emit(90.0)
    f.sensor.emit(91.0)
    assertTrue(states.isEmpty())
    assertEquals(91.0, f.coordinator.getSnapshot().angle!!.asSecondOrNull()!!, 0.0)
    f.coordinator.onLayout(host, feature())
    assertEquals(1, states.size)
    assertEquals(91.0, states.single().angle!!.asSecondOrNull()!!, 0.0)
    f.sensor.emit(92.0)
    f.coordinator.onLayout(host, feature())
    assertEquals(1, states.size)
    assertEquals(listOf(90.0, 91.0, 92.0), f.uiAngles)
    f.coordinator.dispose()
  }

  @Test
  fun logicalDemandSurvivesBackgroundAndOnlyLastStopUnregisters() {
    val f = Fixture()
    f.activate()
    val c = f.coordinator
    c.startAngleUpdates()
    c.startAngleUpdates()
    assertEquals(1, f.sensor.registers)
    assertEquals(2, c.getStatus().requestedConsumerCount)
    c.setForeground(false)
    assertFalse(c.getStatus().sensorRegistered)
    assertEquals(2, c.getStatus().requestedConsumerCount)
    assertEquals(1, f.sensor.unregisters)
    f.sensor.emit(100.0)
    assertTrue(f.uiAngles.isEmpty())
    c.setForeground(true)
    assertEquals(2, f.sensor.registers)
    c.stopAngleUpdates()
    assertTrue(c.getStatus().sensorRegistered)
    c.stopAngleUpdates()
    c.stopAngleUpdates()
    assertFalse(c.getStatus().sensorRegistered)
    assertEquals(0, c.getStatus().requestedConsumerCount)
    assertEquals(2, f.sensor.unregisters)
    c.dispose()
  }

  @Test
  fun physicalRegistrationStaysAccurateWhileAnUnregisterIsInFlight() {
    val f = Fixture()
    f.activate()
    f.coordinator.startAngleUpdates()
    val entered = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val worker = Executors.newSingleThreadExecutor()
    f.sensor.onUnregister = { entered.countDown(); finish.await() }
    try {
      val stop = worker.submit { f.coordinator.setForeground(false) }
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      assertTrue(f.coordinator.getStatus().sensorRegistered)
      assertFalse(f.coordinator.getStatus().isForeground)
      f.sensor.emit(80.0)
      assertTrue(f.uiAngles.isEmpty())
      finish.countDown()
      stop.get(2, TimeUnit.SECONDS)
      assertFalse(f.coordinator.getStatus().sensorRegistered)
    } finally {
      finish.countDown()
      worker.shutdownNow()
      f.coordinator.dispose()
    }
  }

  @Test
  fun replacementWhileRegistrationIsPendingCannotStopTheNewSensor() {
    val f = Fixture()
    val oldHost = f.activate()
    val entered = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val worker = Executors.newSingleThreadExecutor()
    f.sensor.onRegister = { entered.countDown(); finish.await() }
    f.sensor.initialAngle = 10.0
    try {
      val start = worker.submit { f.coordinator.startAngleUpdates() }
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      f.coordinator.detachHost(oldHost)
      f.coordinator.attachHost()
      finish.countDown()
      start.get(2, TimeUnit.SECONDS)
      assertEquals(2, f.sensor.registers)
      assertEquals(1, f.sensor.unregisters)
      assertTrue(f.coordinator.getStatus().sensorRegistered)
      assertEquals(listOf(10.0), f.uiAngles)
      f.sensor.callbacks.first()(100.0)
      assertEquals(listOf(10.0), f.uiAngles)
    } finally {
      finish.countDown()
      worker.shutdownNow()
      f.coordinator.dispose()
    }
  }

  @Test
  fun hostReplacementRejectsOldLayoutAndSensorCallbacks() {
    val f = Fixture()
    val oldHost = f.activate()
    val c = f.coordinator
    c.startAngleUpdates()
    val oldSample = f.sensor.callbacks.single()
    c.onLayout(oldHost, feature())
    val newHost = c.attachHost()
    c.onLayout(oldHost, feature(NativeFoldState.FLAT))
    oldSample(40.0)
    assertNull(c.getSnapshot().geometry)
    assertEquals(Posture.UNKNOWN, c.getSnapshot().posture)
    assertTrue(f.uiAngles.isEmpty())
    c.onLayout(newHost, feature(NativeFoldState.FLAT))
    f.sensor.emit(180.0)
    assertEquals(Posture.FLAT, c.getSnapshot().posture)
    assertEquals(listOf(180.0), f.uiAngles)
    c.detachHost(oldHost)
    assertTrue(c.getStatus().hasHost)
    c.dispose()
  }

  @Test
  fun callbacksAreIndependentExceptionIsolatedAndOutsideStateLock() {
    val f = Fixture()
    f.activate()
    val c = f.coordinator
    c.startAngleUpdates()
    val reader = Executors.newSingleThreadExecutor()
    var count = 0
    val throwing = c.addAngleListener { throw IllegalStateException("consumer failed") }
    val peer = c.addAngleListener {
      assertEquals(it, reader.submit<Double> { c.getSnapshot().angle!!.asSecondOrNull()!! }.get(2, TimeUnit.SECONDS), 0.0)
      count++
    }
    try {
      f.sensor.emit(90.0)
      assertEquals(1, count)
      assertTrue(f.diagnostics.any { it.contains("listener") })
      throwing()
      throwing()
      f.sensor.emit(91.0)
      assertEquals(2, count)
      peer()
      f.sensor.emit(92.0)
      assertEquals(2, count)
    } finally {
      reader.shutdownNow()
      c.dispose()
    }
  }

  @Test
  fun registrationFaultsAreTypedAndDoNotRetryAtSampleCadence() {
    for (security in listOf(false, true)) {
      val f = Fixture()
      f.activate()
      f.sensor.succeeds = false
      if (security) f.sensor.failure = SecurityException("denied")
      val errors = mutableListOf<ClamshellErrorCode>()
      f.coordinator.addErrorListener { errors.add(it.code) }
      f.coordinator.startAngleUpdates()
      assertEquals(listOf(if (security) ClamshellErrorCode.PERMISSIONDENIED else ClamshellErrorCode.SENSORREGISTRATIONFAILED), errors)
      assertFalse(f.coordinator.getStatus().sensorRegistered)
      assertEquals(1, f.coordinator.getStatus().requestedConsumerCount)
      f.sensor.emit(90.0)
      assertTrue(f.uiAngles.isEmpty())
      assertEquals(1, f.sensor.registers)
      f.sensor.failure = null
      f.sensor.succeeds = true
      f.coordinator.setForeground(false)
      f.coordinator.setForeground(true)
      assertTrue(f.coordinator.getStatus().sensorRegistered)
      f.coordinator.dispose()
    }
  }

  @Test
  fun synchronousInitialSamplesWaitForRegistrationSuccess() {
    val f = Fixture()
    f.activate()
    f.sensor.initialAngle = 75.0
    f.coordinator.startAngleUpdates()
    assertEquals(listOf(75.0), f.uiAngles)
    f.coordinator.dispose()
    val failed = Fixture()
    failed.activate()
    failed.sensor.initialAngle = 90.0
    failed.sensor.succeeds = false
    failed.coordinator.startAngleUpdates()
    assertTrue(failed.uiAngles.isEmpty())
    assertNull(failed.coordinator.getSnapshot().angle)
    failed.coordinator.dispose()
  }

  @Test
  fun invalidDataIsReportedWithoutCorruptingSnapshotsOrCapabilities() {
    val f = Fixture()
    val host = f.activate()
    val c = f.coordinator
    c.startAngleUpdates()
    f.sensor.emit(45.0)
    f.sensor.emit(Double.NaN)
    c.onLayout(host, feature().copy(density = 0f))
    assertEquals(45.0, c.getSnapshot().angle!!.asSecondOrNull()!!, 0.0)
    assertNull(c.getSnapshot().geometry)
    assertEquals(2, f.diagnostics.size)
    val caps = c.getCapabilities()
    caps.supportedPostures[0] = Posture.CLOSED
    assertFalse(c.getCapabilities().supportedPostures.contains(Posture.CLOSED))
    c.dispose()
  }

  @Test
  fun concurrentAngleAndLayoutReducersDoNotLoseFields() {
    val workers = Executors.newFixedThreadPool(2)
    try {
      repeat(20) {
        val f = Fixture()
        val host = f.activate()
        f.coordinator.startAngleUpdates()
        val start = CountDownLatch(1)
        val angles = workers.submit {
          start.await()
          repeat(100) { f.sensor.emit(it.toDouble()) }
        }
        val layouts = workers.submit {
          start.await()
          repeat(100) { f.coordinator.onLayout(host, feature(NativeFoldState.FLAT)) }
        }
        start.countDown()
        angles.get(5, TimeUnit.SECONDS)
        layouts.get(5, TimeUnit.SECONDS)
        val state = f.coordinator.getSnapshot()
        assertEquals(99.0, state.angle!!.asSecondOrNull()!!, 0.0)
        assertEquals(Posture.FLAT, state.posture)
        assertEquals(300.0, state.geometry!!.asSecondOrNull()!!.bounds.y, 0.0)
        assertTrue(f.diagnostics.isEmpty())
        f.coordinator.dispose()
      }
    } finally {
      workers.shutdownNow()
    }
  }

  @Test
  fun disposalAndReentrantUnsubscribePreventLaterDelivery() {
    val f = Fixture()
    f.activate()
    val c = f.coordinator
    c.startAngleUpdates()
    var calls = 0
    lateinit var off: () -> Unit
    off = c.addAngleListener { calls++; off() }
    f.sensor.emit(45.0)
    f.sensor.emit(46.0)
    assertEquals(1, calls)
    c.addAngleListener { c.dispose() }
    c.addAngleListener { fail("Listener ran after reentrant disposal") }
    f.sensor.emit(47.0)
    c.dispose()
    off()
    f.sensor.emit(48.0)
    assertEquals(1, f.invalidations)
    assertEquals(1, f.sensor.unregisters)
    assertEquals(0, c.getStatus().requestedConsumerCount)
    assertTrue(c.getStatus().disposed)
    assertThrows(IllegalStateException::class.java) { c.addStateListener {} }
  }
}
