# Compatibility

This matrix records the exact dependency and toolchain combination used for the
Task 1 scaffold verification. Only this combination is currently tested.

| Component | Tested value | Requirement |
|---|---:|---|
| React Native | 0.86.0 | New Architecture enabled |
| Nitro Modules | 0.37.1 | generated Swift/Kotlin/C++ builds |
| Reanimated | 4.6.0 | SharedValue public API |
| Worklets | 0.12.2 | native UI-runtime bridge |
| Android compile/min SDK | 36 / 24 | example debug + release |
| AGP/Kotlin | 8.12.0 / 2.1.20 | generated project |
| WindowManager/coroutines/lifecycle | 1.5.1 / 1.11.0 / 2.11.0 | host binding |
| iOS deployment target | 15.1 | UIHingeInteraction guarded |
| Xcode/Apple SDK | Xcode 26.2 (17C52) / iOS 26.2 | UIKit hinge prototype compiles |

## Resolution evidence

- `yarn.lock` resolves React 19.2.3, React Native 0.86.0, Nitro Modules
  0.37.1, Reanimated 4.6.0, and Worklets 0.12.2. The root and example
  manifests use those same exact versions.
- `example/android/gradle.properties` enables the React Native New
  Architecture. The example root config supplies compile SDK 36, minimum SDK
  24, target SDK 36, NDK 27.1.12297006, and Kotlin 2.1.20 to the library. The
  React Native Gradle plugin catalog pins AGP 8.12.0 and Kotlin 2.1.20. The
  library's standalone fallback remains AGP 8.8.0 with compile/min SDK 34/23.
- `android/build.gradle` declares exact host-binding versions:
  `androidx.window:window:1.5.1`,
  `kotlinx-coroutines-android:1.11.0`, and
  `androidx.lifecycle:lifecycle-runtime-ktx:2.11.0`. These native dependencies
  are Gradle implementation dependencies and therefore resolve transitively
  for Android consumers.
- `example/ios/Podfile.lock` resolves React Core 0.86.0, NitroModules 0.37.1,
  RNReanimated 4.6.0, and RNWorklets 0.12.2. Bundler pins CocoaPods 1.15.2;
  the current host CocoaPods executable is 1.16.2.
- React Native 0.86.0 defines `min_ios_version_supported` as 15.1; both the
  example Podfile and `Clamshell.podspec` consume that value. The podspec
  declares RNReanimated and RNWorklets as native pod dependencies, while
  `install_modules_dependencies` supplies React Native/Nitro integration.
- Toolchain values were read from `xcodebuild -version` and `xcrun` for both
  `iphoneos` and `iphonesimulator`: Xcode 26.2, build 17C52, iOS SDK 26.2.

## Consumer dependency policy

React 19.2.3, React Native 0.86.0, Nitro Modules 0.37.1, Reanimated 4.6.0,
and Worklets 0.12.2 are exact `peerDependencies`. Their duplicate
`devDependencies` entries support local development only and are not bundled
as package runtime dependencies.
