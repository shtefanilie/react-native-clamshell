package com.clamshell

import android.util.Log
import com.margelo.nitro.NitroModules
import com.margelo.nitro.clamshell.ClamshellCapabilities
import com.margelo.nitro.clamshell.ClamshellError
import com.margelo.nitro.clamshell.FoldState
import com.margelo.nitro.clamshell.HybridClamshellSpec
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip

class HybridClamshell : HybridClamshellSpec() {
  private val context = checkNotNull(NitroModules.applicationContext) {
    "Clamshell requires an active Nitro React context"
  }
  private val lifecycle = checkNotNull(context.getNativeModule(ClamshellLifecycleModule::class.java)) {
    "ClamshellLifecycle native module is not linked"
  }
  private val resources = ClamshellResources(context)

  init {
    try {
      lifecycle.track(resources)
      resources.start()
    } catch (error: Exception) {
      resources.close()
      lifecycle.untrack(resources)
      throw error
    }
  }

  @Keep
  @DoNotStrip
  fun getAngleChannelId(): Long = resources.angleChannelId

  override fun dispose() {
    try {
      releaseResources()
    } finally {
      super.dispose()
    }
  }

  override fun getCapabilities() = resources.coordinator.getCapabilities()
  override fun addCapabilitiesListener(cb: (ClamshellCapabilities) -> Unit) =
    resources.coordinator.addCapabilitiesListener(cb)
  override fun getSnapshot() = resources.coordinator.getSnapshot()
  override fun addStateListener(cb: (FoldState) -> Unit) = resources.coordinator.addStateListener(cb)
  override fun startAngleUpdates() = resources.coordinator.startAngleUpdates()
  override fun stopAngleUpdates() = resources.coordinator.stopAngleUpdates()
  override fun addAngleListener(cb: (Double) -> Unit) = resources.coordinator.addAngleListener(cb)
  override fun addErrorListener(cb: (ClamshellError) -> Unit) = resources.coordinator.addErrorListener(cb)

  private fun releaseResources() {
    try {
      resources.close()
    } catch (error: Exception) {
      Log.e("Clamshell", "Failed to release native resources", error)
    } finally {
      lifecycle.untrack(resources)
    }
  }

  @Suppress("unused")
  protected fun finalize() { releaseResources() }
}
