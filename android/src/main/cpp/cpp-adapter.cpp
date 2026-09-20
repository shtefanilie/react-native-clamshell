#include <jni.h>
#include <fbjni/fbjni.h>
#include "ClamshellOnLoad.hpp"
#include "AngleRuntimeBridge.hpp"

namespace {
class JAngleRuntimeBinding : public facebook::jni::JavaClass<JAngleRuntimeBinding> {
 public:
  static constexpr auto kJavaDescriptor = "Lcom/clamshell/AngleRuntimeBinding;";

  static jlong create(facebook::jni::alias_ref<jclass>) {
    return static_cast<jlong>(clamshell::createAngleChannel());
  }
  static void emit(facebook::jni::alias_ref<jclass>, jlong channel, jdouble degrees) {
    clamshell::emitAngleChannel(static_cast<clamshell::AngleChannelId>(channel), degrees);
  }
  static void release(facebook::jni::alias_ref<jclass>, jlong channel) {
    clamshell::releaseAngleChannel(static_cast<clamshell::AngleChannelId>(channel));
  }
  static void registerNatives() {
    javaClassStatic()->registerNatives({
        makeNativeMethod("create", JAngleRuntimeBinding::create),
        makeNativeMethod("emit", JAngleRuntimeBinding::emit),
        makeNativeMethod("release", JAngleRuntimeBinding::release),
    });
  }
};
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return facebook::jni::initialize(vm, []() {
    margelo::nitro::clamshell::registerAllNatives();
    JAngleRuntimeBinding::registerNatives();
    clamshell::registerAngleRuntimeHost();
  });
}
