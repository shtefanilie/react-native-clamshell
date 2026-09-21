package com.clamshell

import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import java.util.concurrent.atomic.AtomicBoolean

internal class ClamshellResources(context: ReactApplicationContext) : AutoCloseable {
  private val closed = AtomicBoolean(false)
  private val channel = AngleRuntimeChannel()
  val angleChannelId: Long get() = channel.id
  val coordinator = ClamshellCoordinator(
    emitUiAngle = channel::emit,
    invalidateUiSinks = channel::close,
    reportDiagnostic = { message, error ->
      if (error == null) Log.w("Clamshell", message) else Log.e("Clamshell", message, error)
    },
  )
  private val host = AndroidHostBinding(context, coordinator)

  fun start() = host.start()

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    try {
      host.close()
    } finally {
      try {
        coordinator.dispose()
      } finally {
        channel.close()
      }
    }
  }
}
