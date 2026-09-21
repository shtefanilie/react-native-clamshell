package com.clamshell

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

internal class AndroidAngleSensor(
  private val manager: SensorManager,
  private val sensor: Sensor,
) : AngleSensorDriver {
  private var listener: SensorEventListener? = null
  private var thread: HandlerThread? = null

  // The coordinator serializes register/unregister; each registration gets its own listener.
  override fun register(onAngle: (Double) -> Unit): Boolean {
    check(listener == null) { "Hinge sensor is already registered" }
    val worker = HandlerThread("ClamshellHinge")
    worker.start()
    thread = worker
    val next = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        onAngle(event.values.firstOrNull()?.toDouble() ?: Double.NaN)
      }
      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    listener = next
    return manager.registerListener(next, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(worker.looper))
  }

  override fun unregister() {
    val previous = listener
    try {
      if (previous != null) manager.unregisterListener(previous)
      listener = null
    } finally {
      thread?.quitSafely()
      thread = null
    }
  }
}
