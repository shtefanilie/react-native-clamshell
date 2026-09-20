package com.clamshell

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.BridgeReactContext
import com.margelo.nitro.clamshell.CapabilityDetectionStatus
import com.margelo.nitro.clamshell.Posture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

class ClamshellTestActivity : ComponentActivity()

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidHostBindingTest {
  private lateinit var context: ReactApplicationContext
  private lateinit var coordinator: ClamshellCoordinator
  private lateinit var binding: AndroidHostBinding
  private val layouts = MutableSharedFlow<WindowLayoutInfo>(replay = 1)
  private var collectors = 0
  private var maximumCollectors = 0
  private var subscriptions = 0

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    context = BridgeReactContext(RuntimeEnvironment.getApplication())
    coordinator = ClamshellCoordinator({}, {}, { _, _ -> })
    layouts.tryEmit(WindowLayoutInfo(emptyList()))
    binding = AndroidHostBinding(
      context, coordinator,
      layouts = {
        flow {
          collectors++
          subscriptions++
          maximumCollectors = maxOf(maximumCollectors, collectors)
          try { emitAll(layouts) } finally { collectors-- }
        }
      },
      rootFinder = { it.findViewWithTag<View>("rn-root") },
    )
  }

  @After
  fun tearDown() {
    binding.close()
    coordinator.dispose()
    shadowOf(Looper.getMainLooper()).idle()
    Dispatchers.resetMain()
  }

  private fun root(activity: ClamshellTestActivity): FrameLayout =
    FrameLayout(activity).also {
      it.tag = "rn-root"
      activity.setContentView(it)
      shadowOf(Looper.getMainLooper()).idle()
    }

  @Test
  fun foregroundRootStartsOneCollectorAndEmptyLayoutResolvesCapability() {
    val activity = Robolectric.buildActivity(ClamshellTestActivity::class.java).setup().visible()
    root(activity.get())
    context.onHostResume(activity.get())
    binding.start()
    binding.onHostResume()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, collectors)
    assertEquals(1, subscriptions)
    assertEquals(CapabilityDetectionStatus.RESOLVED, coordinator.getCapabilities().detectionStatus)
    assertFalse(coordinator.getCapabilities().isFoldable)
    assertTrue(coordinator.getStatus().hasHost)
    binding.close()
    coordinator.dispose()
    assertEquals(0, collectors)
    activity.pause().stop().destroy()
  }

  @Test
  fun activityReplacementAndBackgroundPreserveDemandWithoutDuplicateCollectors() {
    val first = Robolectric.buildActivity(ClamshellTestActivity::class.java).setup().visible()
    root(first.get())
    context.onHostResume(first.get())
    binding.start()
    shadowOf(Looper.getMainLooper()).idle()
    coordinator.startAngleUpdates()
    coordinator.startAngleUpdates()
    context.onHostPause()
    first.pause().stop()
    assertFalse(coordinator.getStatus().isForeground)
    assertEquals(2, coordinator.getStatus().requestedConsumerCount)
    assertEquals(0, collectors)
    context.onHostDestroy()
    first.destroy()

    val second = Robolectric.buildActivity(ClamshellTestActivity::class.java).setup().visible()
    root(second.get())
    context.onHostResume(second.get())
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(coordinator.getStatus().isForeground)
    assertEquals(2, coordinator.getStatus().requestedConsumerCount)
    assertEquals(1, collectors)
    assertEquals(1, maximumCollectors)
    binding.close()
    coordinator.dispose()
    context.onHostResume(second.get())
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(0, collectors)
    assertTrue(coordinator.getStatus().disposed)
    second.pause().stop().destroy()
  }

  @Test
  fun rootMetricsRecomputeGeometryWithoutAnotherWindowSubscription() {
    val activity = Robolectric.buildActivity(ClamshellTestActivity::class.java).setup().visible()
    val root = root(activity.get())
    context.onHostResume(activity.get())
    binding.start()
    shadowOf(Looper.getMainLooper()).idle()
    val foldingFeature = object : FoldingFeature {
      override val bounds = Rect(220, 24, 240, 824)
      override val state = FoldingFeature.State.HALF_OPENED
      override val orientation = FoldingFeature.Orientation.VERTICAL
      override val isSeparating = true
      override val occlusionType = FoldingFeature.OcclusionType.FULL
    }
    layouts.tryEmit(WindowLayoutInfo(listOf(foldingFeature)))
    val before = coordinator.getSnapshot().geometry!!.asSecondOrNull()!!.bounds
    root.offsetLeftAndRight(20)
    activity.get().window.decorView.viewTreeObserver.dispatchOnGlobalLayout()
    val after = coordinator.getSnapshot().geometry!!.asSecondOrNull()!!.bounds
    assertEquals(before.x - 20.0 / root.resources.displayMetrics.density, after.x, 1e-6)
    assertEquals(Posture.HALFOPEN, coordinator.getSnapshot().posture)
    assertEquals(1, subscriptions)

    root(activity.get())
    activity.get().window.decorView.viewTreeObserver.dispatchOnGlobalLayout()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, collectors)
    assertEquals(1, maximumCollectors)
    assertEquals(2, subscriptions)
    binding.close()
    coordinator.dispose()
    activity.pause().stop().destroy()
  }

  @Test
  fun reactContextInvalidationClosesEveryTrackedOwnerOnce() {
    val lifecycle = ClamshellLifecycleModule(context)
    var first = 0
    var second = 0
    val removed = AutoCloseable { first++ }
    lifecycle.track(removed)
    lifecycle.track(AutoCloseable { second++ })
    lifecycle.untrack(removed)
    lifecycle.invalidate()
    lifecycle.invalidate()
    assertEquals(0, first)
    assertEquals(1, second)
    assertThrows(IllegalStateException::class.java) { lifecycle.track(removed) }
  }
}
