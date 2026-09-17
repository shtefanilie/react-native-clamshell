#include "AngleRuntimeBridge.hpp"

#include <NitroModules/HybridObject.hpp>
#include <NitroModules/HybridObjectRegistry.hpp>
#include <jsi/instrumentation.h>
#include <jsi/jsi.h>
#include <worklets/Compat/StableApi.h>

#ifdef __ANDROID__
#include <fbjni/fbjni.h>
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace clamshell {

using facebook::jsi::Runtime;
using facebook::jsi::Value;
using margelo::nitro::HybridObject;
using margelo::nitro::HybridObjectRegistry;

struct AngleRuntimeBridge::Impl {
  std::mutex mutex;
  std::unordered_map<AngleSubscriptionId, std::shared_ptr<UIRuntimeAngleSink>> sinks;
  AngleSubscriptionId nextId{1};
  bool invalidated{false};
};

AngleRuntimeBridge::AngleRuntimeBridge() : impl_(std::make_unique<Impl>()) {}

AngleRuntimeBridge::~AngleRuntimeBridge() = default;

AngleSubscriptionId AngleRuntimeBridge::addSink(
    std::shared_ptr<UIRuntimeAngleSink> sink) {
  if (sink == nullptr) {
    throw std::invalid_argument("AngleRuntimeBridge requires a sink");
  }

  std::lock_guard lock(impl_->mutex);
  if (impl_->invalidated) {
    throw std::runtime_error("AngleRuntimeBridge is invalidated");
  }

  const auto id = impl_->nextId++;
  impl_->sinks.emplace(id, std::move(sink));
  return id;
}

void AngleRuntimeBridge::removeSink(AngleSubscriptionId id) noexcept {
  std::lock_guard lock(impl_->mutex);
  impl_->sinks.erase(id);
}

void AngleRuntimeBridge::emit(double degrees) {
  std::vector<std::shared_ptr<UIRuntimeAngleSink>> snapshot;
  {
    std::lock_guard lock(impl_->mutex);
    if (impl_->invalidated) {
      return;
    }
    snapshot.reserve(impl_->sinks.size());
    for (const auto& [_, sink] : impl_->sinks) {
      snapshot.push_back(sink);
    }
  }

  for (const auto& sink : snapshot) {
    sink->invoke(degrees);
  }
}

void AngleRuntimeBridge::invalidate() noexcept {
  std::lock_guard lock(impl_->mutex);
  impl_->invalidated = true;
  impl_->sinks.clear();
}

namespace {

void scheduleOnUIAttached(
    const std::shared_ptr<worklets::UIScheduler>& scheduler,
    std::function<void()> job) {
#ifdef __ANDROID__
  facebook::jni::ThreadScope::WithClassLoader(
      [&]() { worklets::scheduleOnUI(scheduler, job); });
#else
  worklets::scheduleOnUI(scheduler, job);
#endif
}

class WorkletsAngleSink final : public UIRuntimeAngleSink {
 public:
  WorkletsAngleSink(
      std::weak_ptr<worklets::WorkletRuntime> uiRuntime,
      std::shared_ptr<worklets::UIScheduler> uiScheduler,
      std::shared_ptr<worklets::Serializable> worklet)
      : uiRuntime_(std::move(uiRuntime)),
        uiScheduler_(std::move(uiScheduler)),
        worklet_(std::move(worklet)),
        lifetime_(std::make_shared<std::uint8_t>(0)) {}

  ~WorkletsAngleSink() override {
    lifetime_.reset();
  }

  void invoke(double degrees) override {
    const std::weak_ptr<std::uint8_t> lifetime = lifetime_;
    const auto uiRuntime = uiRuntime_;
    const auto worklet = worklet_;
    scheduleOnUIAttached(
        uiScheduler_, [lifetime, uiRuntime, worklet, degrees]() {
          if (lifetime.expired()) {
            return;
          }
          const auto runtime = uiRuntime.lock();
          if (runtime == nullptr) {
            return;
          }
          worklets::runSyncOnRuntime(runtime, worklet, Value(degrees));
        });
  }

 private:
  std::weak_ptr<worklets::WorkletRuntime> uiRuntime_;
  std::shared_ptr<worklets::UIScheduler> uiScheduler_;
  std::shared_ptr<worklets::Serializable> worklet_;
  std::shared_ptr<std::uint8_t> lifetime_;
};

class AngleRuntimeHostObject final : public HybridObject {
 public:
  AngleRuntimeHostObject() : HybridObject("AngleRuntimeHost") {}

  ~AngleRuntimeHostObject() override {
    stopSynthetic();
    bridge_.invalidate();
  }

  void dispose() override {
    stopSynthetic();
    bridge_.invalidate();
  }

 protected:
  void loadHybridMethods() override {
    HybridObject::loadHybridMethods();
    registerHybrids(this, [](margelo::nitro::Prototype& prototype) {
      prototype.registerRawHybridMethod(
          "addSink", 3, &AngleRuntimeHostObject::addSinkRaw);
      prototype.registerHybridMethod(
          "removeSink", &AngleRuntimeHostObject::removeSink);
      prototype.registerHybridMethod("emit", &AngleRuntimeHostObject::emit);
      prototype.registerHybridMethod(
          "startSynthetic", &AngleRuntimeHostObject::startSynthetic);
      prototype.registerHybridMethod(
          "stopSynthetic", &AngleRuntimeHostObject::stopSynthetic);
      prototype.registerRawHybridMethod(
          "collectGarbage", 0, &AngleRuntimeHostObject::collectGarbageRaw);
      prototype.registerHybridMethod(
          "invalidate", &AngleRuntimeHostObject::invalidate);
    });
  }

 private:
  Value addSinkRaw(
      Runtime& runtime,
      const Value&,
      const Value* arguments,
      std::size_t count) {
    if (count != 3) {
      throw std::invalid_argument(
          "AngleRuntimeHost.addSink expects a worklet and Worklets runtime holders");
    }

    const auto uiRuntime = worklets::getWorkletRuntimeFromHolder(
        runtime, arguments[1].asObject(runtime));
    const auto uiScheduler = worklets::getUISchedulerFromHolder(
        runtime, arguments[2].asObject(runtime));
    const auto worklet = worklets::extractSerializable(
        runtime,
        arguments[0],
        "AngleRuntimeHost.addSink expects a serialized worklet",
        worklets::Serializable::ValueType::WorkletType);

    const auto id = bridge_.addSink(std::make_shared<WorkletsAngleSink>(
        uiRuntime, uiScheduler, worklet));
    return Value(static_cast<double>(id));
  }

  void removeSink(double subscriptionId) {
    bridge_.removeSink(static_cast<AngleSubscriptionId>(subscriptionId));
  }

  void emit(double degrees) {
    bridge_.emit(degrees);
  }

  void startSynthetic(double periodMilliseconds) {
    stopSynthetic();
    syntheticRunning_.store(true);
    const auto period = std::chrono::milliseconds(
        std::max<std::int64_t>(1, static_cast<std::int64_t>(periodMilliseconds)));
    syntheticThread_ = std::thread([this, period]() {
      double sequence = 0;
      std::unique_lock lock(syntheticMutex_);
      while (syntheticRunning_.load()) {
        if (syntheticWake_.wait_for(
                lock, period, [this]() { return !syntheticRunning_.load(); })) {
          break;
        }
        lock.unlock();
        bridge_.emit(++sequence);
        lock.lock();
      }
    });
  }

  void stopSynthetic() {
    syntheticRunning_.store(false);
    syntheticWake_.notify_all();
    if (syntheticThread_.joinable()) {
      syntheticThread_.join();
    }
  }

  Value collectGarbageRaw(Runtime& runtime, const Value&, const Value*, std::size_t) {
    runtime.instrumentation().collectGarbage("AngleRuntimeHost probe");
    return Value::undefined();
  }

  void invalidate() {
    stopSynthetic();
    bridge_.invalidate();
  }

  AngleRuntimeBridge bridge_;
  std::atomic<bool> syntheticRunning_{false};
  std::mutex syntheticMutex_;
  std::condition_variable syntheticWake_;
  std::thread syntheticThread_;
};

} // namespace

void registerAngleRuntimeHost() {
  HybridObjectRegistry::registerHybridObjectConstructor(
      "AngleRuntimeHost", []() -> std::shared_ptr<HybridObject> {
        return createAngleRuntimeHostObject();
      });
}

std::shared_ptr<HybridObject> createAngleRuntimeHostObject() {
  return std::make_shared<AngleRuntimeHostObject>();
}

} // namespace clamshell
