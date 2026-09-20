package com.clamshell

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.margelo.nitro.clamshell.ClamshellOnLoad

class ClamshellPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == ClamshellLifecycleModule.NAME) ClamshellLifecycleModule(reactContext) else null

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
    mapOf(ClamshellLifecycleModule.NAME to ReactModuleInfo(
      ClamshellLifecycleModule.NAME, ClamshellLifecycleModule.NAME,
      canOverrideExistingModule = false, needsEagerInit = false,
      isCxxModule = false, isTurboModule = true,
    ))
  }

  companion object {
    init {
      ClamshellOnLoad.initializeNative()
    }
  }
}
