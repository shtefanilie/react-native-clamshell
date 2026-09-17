#include <jni.h>
#include <fbjni/fbjni.h>
#include "ClamshellOnLoad.hpp"
#include "AngleRuntimeBridge.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return facebook::jni::initialize(vm, []() {
    margelo::nitro::clamshell::registerAllNatives();
    clamshell::registerAngleRuntimeHost();
  });
}
