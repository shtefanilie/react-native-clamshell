package com.clamshell

import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import java.util.concurrent.atomic.AtomicLong

@Keep
@DoNotStrip
@Suppress("KotlinJniMissingFunction")
internal object AngleRuntimeBinding {
  @JvmStatic external fun create(): Long
  @JvmStatic external fun emit(channel: Long, degrees: Double)
  @JvmStatic external fun release(channel: Long)
}

internal class AngleRuntimeChannel : AutoCloseable {
  private val channel = AtomicLong(AngleRuntimeBinding.create())
  val id: Long get() = channel.get()

  fun emit(degrees: Double) {
    val current = channel.get()
    if (current != 0L) AngleRuntimeBinding.emit(current, degrees)
  }

  override fun close() {
    val current = channel.getAndSet(0)
    if (current != 0L) AngleRuntimeBinding.release(current)
  }
}
