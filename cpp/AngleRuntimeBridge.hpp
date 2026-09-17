#pragma once

#include <cstdint>
#include <memory>

namespace margelo::nitro {
class HybridObject;
}

namespace clamshell {

using AngleSubscriptionId = std::uint64_t;

class UIRuntimeAngleSink {
 public:
  virtual ~UIRuntimeAngleSink() = default;
  virtual void invoke(double degrees) = 0;
};

class AngleRuntimeBridge final {
 public:
  AngleRuntimeBridge();
  ~AngleRuntimeBridge();

  AngleRuntimeBridge(const AngleRuntimeBridge&) = delete;
  AngleRuntimeBridge& operator=(const AngleRuntimeBridge&) = delete;

  AngleSubscriptionId addSink(std::shared_ptr<UIRuntimeAngleSink> sink);
  void removeSink(AngleSubscriptionId id) noexcept;
  void emit(double degrees);
  void invalidate() noexcept;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

void registerAngleRuntimeHost();
std::shared_ptr<margelo::nitro::HybridObject> createAngleRuntimeHostObject();

} // namespace clamshell
