package com.clamshell

import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.turbomodule.core.interfaces.TurboModule

@ReactModule(name = ClamshellLifecycleModule.NAME)
internal class ClamshellLifecycleModule(context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context), TurboModule {
  private val resources = mutableSetOf<AutoCloseable>()
  private var invalidated = false

  override fun getName() = NAME

  fun track(resource: AutoCloseable) = synchronized(resources) {
    check(!invalidated) { "Clamshell React context is invalidated" }
    resources.add(resource)
  }

  fun untrack(resource: AutoCloseable) = synchronized(resources) {
    resources.remove(resource)
  }

  override fun invalidate() {
    val snapshot = synchronized(resources) {
      invalidated = true
      resources.toList().also { resources.clear() }
    }
    try {
      for (resource in snapshot) {
        try {
          resource.close()
        } catch (error: Exception) {
          Log.e(NAME, "Failed to release Clamshell resources during React teardown", error)
        }
      }
    } finally {
      super.invalidate()
    }
  }

  companion object { const val NAME = "ClamshellLifecycle" }
}
