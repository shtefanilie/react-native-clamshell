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
#include <cmath>
#include <condition_variable>
#include <limits>
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

constexpr AngleSubscriptionId kMaxSafeSubscriptionId = 9007199254740991ULL;

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
  if (impl_->nextId > kMaxSafeSubscriptionId) {
    throw std::overflow_error("AngleRuntimeBridge exhausted safe subscription IDs");
  }

  const auto id = impl_->nextId++;
  impl_->sinks.emplace(id, std::move(sink));
  return id;
}

void AngleRuntimeBridge::removeSink(AngleSubscriptionId id) noexcept {
  std::shared_ptr<UIRuntimeAngleSink> sink;
  {
    std::lock_guard lock(impl_->mutex);
    const auto iterator = impl_->sinks.find(id);
    if (iterator == impl_->sinks.end()) {
      return;
    }
    sink = iterator->second;
  }
  sink->deactivateAndWait();
  std::lock_guard lock(impl_->mutex);
  const auto iterator = impl_->sinks.find(id);
  if (iterator != impl_->sinks.end() && iterator->second == sink) {
    impl_->sinks.erase(iterator);
  }
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
  std::vector<std::shared_ptr<UIRuntimeAngleSink>> sinks;
  {
    std::lock_guard lock(impl_->mutex);
    impl_->invalidated = true;
    sinks.reserve(impl_->sinks.size());
    for (const auto& [_, sink] : impl_->sinks) {
      sinks.push_back(sink);
    }
  }
  for (const auto& sink : sinks) {
    sink->deactivateAndWait();
  }
  std::lock_guard lock(impl_->mutex);
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
        state_(std::make_shared<State>()) {}

  ~WorkletsAngleSink() override {
    deactivateAndWait();
  }

  void invoke(double degrees) override {
    {
      std::lock_guard lock(state_->mutex);
      if (!state_->active) {
        return;
      }
      ++state_->pending;
    }

    const auto state = state_;
    const auto completed = std::make_shared<std::atomic<bool>>(false);
    const auto uiRuntime = uiRuntime_;
    const auto worklet = worklet_;
    try {
      scheduleOnUIAttached(
          uiScheduler_, [state, completed, uiRuntime, worklet, degrees]() {
            bool active;
            {
              std::lock_guard lock(state->mutex);
              active = state->active;
            }
            try {
              if (active) {
                const auto runtime = uiRuntime.lock();
                if (runtime != nullptr) {
                  worklets::runSyncOnRuntime(runtime, worklet, Value(degrees));
                }
              }
            } catch (...) {
              complete(state, completed);
              throw;
            }
            complete(state, completed);
          });
    } catch (...) {
      complete(state, completed);
      throw;
    }
  }

  void deactivateAndWait() noexcept override {
    std::unique_lock lock(state_->mutex);
    state_->active = false;
    state_->condition.notify_all();
    state_->condition.wait(lock, [this]() { return state_->pending == 0; });
  }

  void waitUntilInactive() {
    std::unique_lock lock(state_->mutex);
    state_->condition.wait(lock, [this]() { return !state_->active; });
  }

  void waitUntilIdle() {
    std::unique_lock lock(state_->mutex);
    state_->condition.wait(lock, [this]() { return state_->pending == 0; });
  }

  std::shared_ptr<worklets::UIScheduler> getScheduler() const {
    return uiScheduler_;
  }

 private:
  struct State {
    std::mutex mutex;
    std::condition_variable condition;
    bool active{true};
    std::size_t pending{0};
  };

  static void complete(
      const std::shared_ptr<State>& state,
      const std::shared_ptr<std::atomic<bool>>& completed) noexcept {
    if (completed->exchange(true)) {
      return;
    }
    std::lock_guard lock(state->mutex);
    if (--state->pending == 0) {
      state->condition.notify_all();
    }
  }

  std::weak_ptr<worklets::WorkletRuntime> uiRuntime_;
  std::shared_ptr<worklets::UIScheduler> uiScheduler_;
  std::shared_ptr<worklets::Serializable> worklet_;
  std::shared_ptr<State> state_;
};

AngleSubscriptionId validateSubscriptionId(double value) {
  if (!std::isfinite(value) || value < 1 ||
      value > static_cast<double>(kMaxSafeSubscriptionId) ||
      std::floor(value) != value) {
    throw std::invalid_argument(
        "AngleRuntimeHost subscription ID must be a positive safe integer");
  }
  return static_cast<AngleSubscriptionId>(value);
}

void validateDegrees(double degrees) {
  if (!std::isfinite(degrees)) {
    throw std::invalid_argument("AngleRuntimeHost degrees must be finite");
  }
}

struct UIBlocker {
  std::mutex mutex;
  std::condition_variable condition;
  bool entered{false};
  bool released{false};
};

std::shared_ptr<UIBlocker> blockUI(
    const std::shared_ptr<worklets::UIScheduler>& scheduler) {
  auto blocker = std::make_shared<UIBlocker>();
  scheduleOnUIAttached(scheduler, [blocker]() {
    std::unique_lock lock(blocker->mutex);
    blocker->entered = true;
    blocker->condition.notify_all();
    blocker->condition.wait(lock, [blocker]() { return blocker->released; });
  });
  std::unique_lock lock(blocker->mutex);
  blocker->condition.wait(lock, [blocker]() { return blocker->entered; });
  return blocker;
}

void releaseUI(const std::shared_ptr<UIBlocker>& blocker) {
  std::lock_guard lock(blocker->mutex);
  blocker->released = true;
  blocker->condition.notify_all();
}

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
          "emitConcurrentlyAndRemove",
          &AngleRuntimeHostObject::emitConcurrentlyAndRemove);
      prototype.registerHybridMethod(
          "emitConcurrentlyAndInvalidate",
          &AngleRuntimeHostObject::emitConcurrentlyAndInvalidate);
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

    auto sink = std::make_shared<WorkletsAngleSink>(
        uiRuntime, uiScheduler, worklet);
    const auto id = bridge_.addSink(sink);
    {
      std::lock_guard lock(sinksMutex_);
      sinks_[id] = sink;
    }
    return Value(static_cast<double>(id));
  }

  void removeSink(double subscriptionId) {
    const auto id = validateSubscriptionId(subscriptionId);
    bridge_.removeSink(id);
    std::lock_guard lock(sinksMutex_);
    sinks_.erase(id);
  }

  void emit(double degrees) {
    validateDegrees(degrees);
    bridge_.emit(degrees);
  }

  void emitConcurrentlyAndRemove(double subscriptionId, double degrees) {
    const auto id = validateSubscriptionId(subscriptionId);
    validateDegrees(degrees);
    std::shared_ptr<WorkletsAngleSink> sink;
    std::vector<std::shared_ptr<WorkletsAngleSink>> sinks;
    {
      std::lock_guard lock(sinksMutex_);
      const auto iterator = sinks_.find(id);
      if (iterator == sinks_.end()) {
        throw std::invalid_argument("AngleRuntimeHost subscription does not exist");
      }
      sink = iterator->second;
      sinks.reserve(sinks_.size());
      for (const auto& [_, activeSink] : sinks_) {
        sinks.push_back(activeSink);
      }
    }

    const auto blocker = blockUI(sink->getScheduler());
    try {
      bridge_.emit(degrees);
    } catch (...) {
      releaseUI(blocker);
      throw;
    }
    std::thread remover;
    try {
      remover = std::thread([this, id]() { bridge_.removeSink(id); });
    } catch (...) {
      releaseUI(blocker);
      throw;
    }
    sink->waitUntilInactive();
    releaseUI(blocker);
    remover.join();
    for (const auto& activeSink : sinks) {
      activeSink->waitUntilIdle();
    }
    std::lock_guard lock(sinksMutex_);
    sinks_.erase(id);
  }

  void emitConcurrentlyAndInvalidate(double degrees) {
    validateDegrees(degrees);
    std::vector<std::shared_ptr<WorkletsAngleSink>> sinks;
    {
      std::lock_guard lock(sinksMutex_);
      if (sinks_.empty()) {
        throw std::runtime_error("AngleRuntimeHost has no active sinks");
      }
      sinks.reserve(sinks_.size());
      for (const auto& [_, sink] : sinks_) {
        sinks.push_back(sink);
      }
    }

    const auto blocker = blockUI(sinks.front()->getScheduler());
    try {
      bridge_.emit(degrees);
    } catch (...) {
      releaseUI(blocker);
      throw;
    }
    std::thread invalidator;
    try {
      invalidator = std::thread([this]() { bridge_.invalidate(); });
    } catch (...) {
      releaseUI(blocker);
      throw;
    }
    for (const auto& sink : sinks) {
      sink->waitUntilInactive();
    }
    releaseUI(blocker);
    invalidator.join();
    std::lock_guard lock(sinksMutex_);
    sinks_.clear();
  }

  void startSynthetic(double periodMilliseconds) {
    if (!std::isfinite(periodMilliseconds) || periodMilliseconds < 0 ||
        std::floor(periodMilliseconds) != periodMilliseconds ||
        periodMilliseconds >
            static_cast<double>(std::numeric_limits<std::int64_t>::max())) {
      throw std::invalid_argument(
          "AngleRuntimeHost synthetic period must be a finite nonnegative integer");
    }
    stopSynthetic();
    syntheticRunning_.store(true);
    const auto period = std::chrono::milliseconds(
        std::max<std::int64_t>(1, static_cast<std::int64_t>(periodMilliseconds)));
    syntheticThread_ = std::thread([this, period]() {
      try {
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
      } catch (...) {
        syntheticRunning_.store(false);
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
    std::lock_guard lock(sinksMutex_);
    sinks_.clear();
  }

  AngleRuntimeBridge bridge_;
  std::mutex sinksMutex_;
  std::unordered_map<AngleSubscriptionId, std::shared_ptr<WorkletsAngleSink>> sinks_;
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
