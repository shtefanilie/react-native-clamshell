package com.clamshell

import com.margelo.nitro.clamshell.AngleRange
import com.margelo.nitro.clamshell.CapabilityDetectionStatus
import com.margelo.nitro.clamshell.ClamshellCapabilities
import com.margelo.nitro.clamshell.ClamshellError
import com.margelo.nitro.clamshell.ClamshellErrorCode
import com.margelo.nitro.clamshell.FoldOrientation
import com.margelo.nitro.clamshell.FoldState
import com.margelo.nitro.clamshell.Posture
import com.margelo.nitro.clamshell.Variant_NullType_AngleRange
import com.margelo.nitro.clamshell.Variant_NullType_Double
import com.margelo.nitro.clamshell.Variant_NullType_FoldGeometry
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface AngleSensorDriver {
  fun register(onAngle: (Double) -> Unit): Boolean
  fun unregister()
}

internal data class FoldFeatureSample(
  val state: NativeFoldState,
  val orientation: NativeFoldOrientation,
  val boundsPx: FoldBoundsPx,
  val rootOriginPx: RootOriginPx,
  val density: Float,
  val isSeparating: Boolean,
  val isFullyOccluding: Boolean,
)

internal data class CoordinatorStatus(
  val requestedConsumerCount: Int,
  val sensorRegistered: Boolean,
  val isForeground: Boolean,
  val hasHost: Boolean,
  val disposed: Boolean,
)

internal class ClamshellCoordinator(
  private val emitUiAngle: (Double) -> Unit,
  private val invalidateUiSinks: () -> Unit,
  private val reportDiagnostic: (String, Throwable?) -> Unit,
) {
  private class Listener<T>(var callback: ((T) -> Unit)?) {
    val invocation = ReentrantLock()
  }

  private class Registration(val driver: AngleSensorDriver) {
    var began = false
    var failed = false
    var cleanupQueued = false
    var initialAngle: Double? = null
  }

  private val lock = ReentrantLock()
  private val idle = lock.newCondition()
  private val effects = ArrayDeque<() -> Unit>()
  private var draining = false
  private var drainThread: Thread? = null
  private var nextListenerId = 0L
  private val capabilityListeners = linkedMapOf<Long, Listener<ClamshellCapabilities>>()
  private val stateListeners = linkedMapOf<Long, Listener<FoldState>>()
  private val angleListeners = linkedMapOf<Long, Listener<Double>>()
  private val errorListeners = linkedMapOf<Long, Listener<ClamshellError>>()
  private var disposed = false
  private var hostGeneration = 0L
  private var hasHost = false
  private var isForeground = false
  private var layoutResolved = false
  private var hasFoldFeature = false
  private var sensor: AngleSensorDriver? = null
  private var angleRange: Variant_NullType_AngleRange? = null
  private var registration: Registration? = null
  private var physicalRegistration: Registration? = null
  private val sensorRegistered: Boolean get() = physicalRegistration != null
  private var requestedConsumerCount = 0
  private var state = FoldState(null, Posture.UNKNOWN, FoldOrientation.NONE, null)
  private var capabilities = ClamshellCapabilities(
    CapabilityDetectionStatus.PENDING, false, false, null, emptyArray(), false,
  )

  fun getSnapshot(): FoldState = lock.withLock { state }

  fun getCapabilities(): ClamshellCapabilities = lock.withLock {
    capabilities.copy(supportedPostures = capabilities.supportedPostures.copyOf())
  }

  fun getStatus(): CoordinatorStatus = lock.withLock {
    CoordinatorStatus(requestedConsumerCount, sensorRegistered, isForeground, hasHost, disposed)
  }

  fun addCapabilitiesListener(callback: (ClamshellCapabilities) -> Unit): () -> Unit =
    addListener(capabilityListeners, callback)

  fun addStateListener(callback: (FoldState) -> Unit): () -> Unit =
    addListener(stateListeners, callback)

  fun addAngleListener(callback: (Double) -> Unit): () -> Unit =
    addListener(angleListeners, callback)

  fun addErrorListener(callback: (ClamshellError) -> Unit): () -> Unit =
    addListener(errorListeners, callback)

  fun setSensor(driver: AngleSensorDriver?, maximumAngle: Double?, featureFlag: Boolean) = change {
    if (disposed) return@change
    if (sensor !== driver) {
      retireRegistration()
    }
    if (driver == null) state = state.copy(angle = null)
    sensor = driver
    angleRange = if (driver != null && maximumAngle != null &&
      maximumAngle.isFinite() && maximumAngle > 0.0 && maximumAngle <= 360.0
    ) {
      Variant_NullType_AngleRange.Second(AngleRange(0.0, maximumAngle))
    } else {
      if (driver != null && maximumAngle != null) {
        diagnostic("Invalid hinge sensor maximum angle: $maximumAngle")
      }
      null
    }
    if ((driver != null) != featureFlag) {
      diagnostic("Hinge sensor instance disagrees with package feature flag (instance=${driver != null}, flag=$featureFlag)")
    }
    updateCapabilities()
    reconcileRegistration()
  }

  fun attachHost(): Long = change {
    check(!disposed) { "Clamshell coordinator is disposed" }
    advanceHostGeneration()
    retireRegistration()
    hasHost = true
    resetLayout()
    reconcileRegistration()
    hostGeneration
  }

  fun detachHost(host: Long) = change {
    if (disposed || !hasHost || host != hostGeneration) return@change
    advanceHostGeneration()
    hasHost = false
    resetLayout()
    reconcileRegistration()
  }

  fun setForeground(foreground: Boolean) = change {
    if (disposed || isForeground == foreground) return@change
    isForeground = foreground
    reconcileRegistration()
  }

  fun onLayout(host: Long, feature: FoldFeatureSample?) = change {
    if (disposed || !hasHost || host != hostGeneration) return@change
    val geometry = try {
      feature?.let {
        featureToGeometry(it.boundsPx, it.rootOriginPx, it.density, it.isSeparating, it.isFullyOccluding)
      }
    } catch (error: IllegalArgumentException) {
      diagnostic("Invalid fold layout sample", error)
      return@change
    }
    layoutResolved = true
    hasFoldFeature = feature != null
    updateCapabilities()
    updateState(state.copy(
      posture = featureToPosture(feature?.state),
      orientation = featureToOrientation(feature?.orientation),
      geometry = geometry?.let { Variant_NullType_FoldGeometry.Second(it) },
    ))
  }

  fun startAngleUpdates() = change {
    check(!disposed) { "Clamshell coordinator is disposed" }
    check(requestedConsumerCount < Int.MAX_VALUE) { "Too many hinge angle consumers" }
    requestedConsumerCount++
    reconcileRegistration(retryFailed = true)
  }

  fun stopAngleUpdates() = change {
    if (disposed) return@change
    if (requestedConsumerCount > 0) requestedConsumerCount--
    reconcileRegistration()
  }

  fun reportError(error: ClamshellError, cause: Throwable? = null) = change {
    if (!disposed) queueError(error, cause)
  }

  fun dispose() {
    val released: List<Listener<*>> = change {
      if (disposed) {
        physicalRegistration?.let { queueUnregister(it) }
        return@change emptyList()
      }
      disposed = true
      requestedConsumerCount = 0
      hasHost = false
      isForeground = false
      sensor = null
      val callbacks = capabilityListeners.values.toList() + stateListeners.values.toList() +
        angleListeners.values.toList() + errorListeners.values.toList()
      capabilityListeners.clear()
      stateListeners.clear()
      angleListeners.clear()
      errorListeners.clear()
      retireRegistration()
      physicalRegistration?.let { queueUnregister(it) }
      effects.add(invalidateUiSinks)
      callbacks
    }
    for (listener in released) {
      listener.invocation.withLock { listener.callback = null }
    }
    // Reentrant disposal takes effect immediately; its final IO runs after the callback.
    lock.withLock {
      while (draining && drainThread !== Thread.currentThread()) idle.awaitUninterruptibly()
    }
  }

  private fun resetLayout() {
    layoutResolved = false
    hasFoldFeature = false
    updateCapabilities()
    updateState(state.copy(posture = Posture.UNKNOWN, orientation = FoldOrientation.NONE, geometry = null))
  }

  private fun updateCapabilities() {
    val foldable = sensor != null || hasFoldFeature
    val next = ClamshellCapabilities(
      detectionStatus = if (sensor != null || layoutResolved) CapabilityDetectionStatus.RESOLVED
        else CapabilityDetectionStatus.PENDING,
      isFoldable = foldable,
      hasContinuousAngle = sensor != null,
      angleRange = angleRange,
      supportedPostures = if (foldable) arrayOf(Posture.HALFOPEN, Posture.FLAT) else emptyArray(),
      hasFoldGeometry = hasFoldFeature,
    )
    if (next == capabilities) return
    capabilities = next
    val host = hostGeneration
    queueListeners(capabilityListeners, { next.copy(supportedPostures = next.supportedPostures.copyOf()) }) {
      host == hostGeneration
    }
  }

  private fun updateState(next: FoldState) {
    val changed = state.posture != next.posture ||
      state.orientation != next.orientation || state.geometry != next.geometry
    state = next
    if (!changed) return
    val host = hostGeneration
    queueListeners(stateListeners, { next }) { host == hostGeneration }
  }

  private fun wantsRegistration(): Boolean =
    !disposed && requestedConsumerCount > 0 && isForeground && hasHost && sensor != null

  private fun reconcileRegistration(retryFailed: Boolean = false) {
    val current = registration
    if (current != null && (!wantsRegistration() || current.driver !== sensor ||
        (retryFailed && current.failed))) {
      retireRegistration()
    }
    physicalRegistration?.let {
      if (it !== registration) queueUnregister(it)
    }
    val driver = sensor
    if (wantsRegistration() && registration == null && driver != null) {
      val next = Registration(driver)
      registration = next
      effects.add { beginRegistration(next) }
    }
  }

  private fun retireRegistration() {
    val previous = registration
    registration = null
    if (previous != null) queueUnregister(previous)
  }

  private fun queueUnregister(previous: Registration) {
    if (previous.cleanupQueued) return
    previous.cleanupQueued = true
    effects.add {
      if (lock.withLock { previous.began }) {
        try {
          previous.driver.unregister()
        } catch (error: RuntimeException) {
          change { previous.cleanupQueued = false }
          throw error
        }
        change {
          if (physicalRegistration === previous) physicalRegistration = null
        }
      }
    }
  }

  private fun beginRegistration(next: Registration) {
    val shouldStart = change {
      if (registration !== next || !wantsRegistration()) return@change false
      if (physicalRegistration != null) {
        next.failed = true
        queueError(ClamshellError(
          ClamshellErrorCode.SENSORREGISTRATIONFAILED,
          "Previous hinge sensor registration has not stopped",
        ))
        return@change false
      }
      next.began = true
      true
    }
    if (!shouldStart) return
    var failure: ClamshellError? = null
    var cause: RuntimeException? = null
    val registered = try {
      next.driver.register { onAngle(next, it) }
    } catch (error: SecurityException) {
      cause = error
      failure = ClamshellError(ClamshellErrorCode.PERMISSIONDENIED, error.message ?: "Hinge sensor access denied")
      false
    } catch (error: RuntimeException) {
      cause = error
      failure = ClamshellError(ClamshellErrorCode.SENSORREGISTRATIONFAILED, error.message ?: "Hinge sensor registration failed")
      false
    }
    change {
      if (registered) physicalRegistration = next
      // Retirement already queued cleanup before any replacement registration.
      if (registration !== next || disposed) return@change
      if (registered) {
        next.initialAngle?.let { publishAngle(next, it) }
        next.initialAngle = null
      } else {
        next.failed = true
        next.initialAngle = null
        queueUnregister(next)
        queueError(failure ?: ClamshellError(
          ClamshellErrorCode.SENSORREGISTRATIONFAILED, "Hinge sensor registration returned false",
        ), cause)
      }
    }
  }

  private fun onAngle(source: Registration, rawDegrees: Double) = change {
    if (registration !== source || source.failed || !wantsRegistration()) return@change
    val degrees = try {
      rawDegreesToCanonical(rawDegrees)
    } catch (error: IllegalArgumentException) {
      diagnostic("Invalid hinge angle sample: $rawDegrees", error)
      return@change
    }
    val reportedMaximum = angleRange?.asSecondOrNull()?.max
    if (degrees != rawDegrees) {
      diagnostic("Hinge angle outside documented range: $rawDegrees (clamped to $degrees)")
    } else if (reportedMaximum != null && degrees > reportedMaximum) {
      diagnostic("Hinge angle $degrees exceeds the sensor's reported maximum $reportedMaximum")
    }
    if (physicalRegistration === source) publishAngle(source, degrees) else source.initialAngle = degrees
  }

  private fun publishAngle(source: Registration, degrees: Double) {
    state = state.copy(angle = Variant_NullType_Double.Second(degrees))
    val valid = { registration === source && physicalRegistration === source && wantsRegistration() }
    effects.add {
      if (lock.withLock { valid() }) emitUiAngle(degrees)
    }
    queueListeners(angleListeners, { degrees }, valid)
  }

  private fun queueError(error: ClamshellError, cause: Throwable? = null) {
    diagnostic("${error.code}: ${error.message}", cause)
    queueListeners(errorListeners, { error })
  }

  private fun diagnostic(message: String, error: Throwable? = null) {
    effects.add { reportDiagnostic(message, error) }
  }

  private fun <T> addListener(
    registry: MutableMap<Long, Listener<T>>,
    callback: (T) -> Unit,
  ): () -> Unit {
    val listener = Listener(callback)
    val id = lock.withLock {
      check(!disposed) { "Clamshell coordinator is disposed" }
      check(nextListenerId < Long.MAX_VALUE) { "Hinge subscription IDs exhausted" }
      (++nextListenerId).also { registry[it] = listener }
    }
    val active = AtomicBoolean(true)
    return {
      if (active.compareAndSet(true, false)) {
        lock.withLock { registry.remove(id) }
        // Wait for an already-running callback without holding the state lock.
        listener.invocation.withLock { listener.callback = null }
      }
    }
  }

  private fun <T> queueListeners(
    registry: MutableMap<Long, Listener<T>>,
    value: () -> T,
    valid: () -> Boolean = { true },
  ) {
    for ((id, listener) in registry.toList()) {
      effects.add {
        listener.invocation.withLock {
          val deliver = lock.withLock { !disposed && registry[id] === listener && valid() }
          if (deliver) {
            try {
              listener.callback?.invoke(value())
            } catch (error: Exception) {
              reportDiagnostic("Clamshell listener failed", error)
            }
          }
        }
      }
    }
  }

  private fun advanceHostGeneration() {
    check(hostGeneration < Long.MAX_VALUE) { "Hinge host IDs exhausted" }
    hostGeneration++
  }

  private fun <T> change(reduce: () -> T): T {
    var runEffects = false
    val result = lock.withLock {
      val value = reduce()
      if (!draining && effects.isNotEmpty()) {
        draining = true
        drainThread = Thread.currentThread()
        runEffects = true
      }
      value
    }
    if (runEffects) drainEffects()
    return result
  }

  private fun drainEffects() {
    val owner = Thread.currentThread()
    try {
      while (true) {
        val effect = lock.withLock {
          if (effects.isEmpty()) {
            draining = false
            drainThread = null
            idle.signalAll()
            null
          } else effects.removeFirst()
        } ?: return
        try {
          effect()
        } catch (error: Exception) {
          reportDiagnostic("Clamshell side effect failed", error)
        }
      }
    } finally {
      lock.withLock {
        if (drainThread === owner) {
          draining = false
          drainThread = null
          idle.signalAll()
        }
      }
    }
  }
}
