package com.clamshell

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.facebook.react.ReactRootView
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.common.LifecycleState
import com.margelo.nitro.clamshell.ClamshellError
import com.margelo.nitro.clamshell.ClamshellErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class AndroidHostBinding(
  private val context: ReactApplicationContext,
  private val coordinator: ClamshellCoordinator,
  private val layouts: (Activity) -> Flow<WindowLayoutInfo> = {
    WindowInfoTracker.getOrCreate(context).windowLayoutInfo(it)
  },
  private val rootFinder: (View) -> View? = { findReactRoot(it, context) },
) : LifecycleEventListener, AutoCloseable {
  private data class Host(val activity: Activity, val root: View, val generation: Long)
  private data class Metrics(val x: Int, val y: Int, val density: Float, val width: Int, val height: Int)

  private val gate = Any()
  private val main = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
    CoroutineExceptionHandler { _, error -> Log.e(TAG, "Host lifecycle collection failed", error) })
  private val targets = MutableStateFlow<Host?>(null)
  private var closed = false
  private var started = false
  private var resumed = false
  private var activity: Activity? = null
  private var decor: View? = null
  private var observedTree: ViewTreeObserver? = null
  private var host: Host? = null
  private var latestLayout: WindowLayoutInfo? = null
  private var lastMetrics: Metrics? = null

  private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { withOpen { refresh() } }
  private val attachmentListener = object : View.OnAttachStateChangeListener {
    override fun onViewAttachedToWindow(view: View) { withOpen { refresh() } }
    override fun onViewDetachedFromWindow(view: View) { withOpen { refresh() } }
  }

  fun start() = withOpen {
    if (started) return@withOpen
    started = true
    probeSensor()
    scope.launch {
      targets.collectLatest { target ->
        if (target == null) return@collectLatest
        val owner = target.activity as LifecycleOwner
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
          try {
            layouts(target.activity).collect { info ->
              synchronized(gate) {
                if (!closed && host === target) {
                  latestLayout = info
                  publishLayout(target, info)
                }
              }
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: SecurityException) {
            coordinator.reportError(
              ClamshellError(ClamshellErrorCode.PERMISSIONDENIED, error.message ?: "Window layout access denied"),
              error,
            )
          } catch (error: RuntimeException) {
            Log.e(TAG, "WindowManager collection failed; detection is not resolved as unsupported", error)
          }
        }
      }
    }
    context.addLifecycleEventListener(this)
    resumed = context.lifecycleState == LifecycleState.RESUMED
    refresh()
  }

  override fun onHostResume() = withOpen {
    resumed = true
    refresh()
  }

  override fun onHostPause() = withOpen {
    resumed = false
    coordinator.setForeground(false)
  }

  override fun onHostDestroy() = withOpen {
    resumed = false
    coordinator.setForeground(false)
    detachHost()
    removeDecorObserver()
    activity = null
  }

  private fun probeSensor() {
    var featureFlag = false
    try {
      featureFlag = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
      val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
      val sensor = manager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
      coordinator.setSensor(
        if (manager != null && sensor != null) AndroidAngleSensor(manager, sensor) else null,
        sensor?.maximumRange?.toDouble(),
        featureFlag,
      )
    } catch (error: SecurityException) {
      coordinator.setSensor(null, null, featureFlag)
      coordinator.reportError(
        ClamshellError(ClamshellErrorCode.PERMISSIONDENIED, error.message ?: "Hinge sensor access denied"),
        error,
      )
    }
  }

  private fun refresh() {
    val nextActivity = context.currentActivity?.takeUnless { it.isDestroyed || it.isFinishing }
    if (activity !== nextActivity) {
      detachHost()
      removeDecorObserver()
      activity = nextActivity
      if (nextActivity != null) {
        if (nextActivity !is LifecycleOwner) {
          Log.e(TAG, "React Activity must implement LifecycleOwner for fold detection")
          return
        }
        decor = nextActivity.window.decorView
        observedTree = decor!!.viewTreeObserver
        observedTree!!.addOnGlobalLayoutListener(layoutListener)
      }
    }
    val root = decor?.let(rootFinder)?.takeIf { it.isAttachedToWindow }
    if (nextActivity == null || nextActivity !is LifecycleOwner || root == null) {
      coordinator.setForeground(false)
      detachHost()
      return
    }
    val previous = host
    if (previous?.activity !== nextActivity || previous.root !== root) {
      detachHost()
      val next = Host(nextActivity, root, coordinator.attachHost())
      host = next
      root.addOnAttachStateChangeListener(attachmentListener)
      targets.value = next
    } else if (metrics(root) != lastMetrics) {
      latestLayout?.let { publishLayout(previous, it) }
    }
    coordinator.setForeground(resumed)
  }

  private fun publishLayout(target: Host, info: WindowLayoutInfo) {
    if (host !== target || !target.root.isAttachedToWindow) return
    val metrics = metrics(target.root)
    lastMetrics = metrics
    val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
    val sample = feature?.let {
      val bounds = it.bounds
      FoldFeatureSample(
        state = when (it.state) {
          FoldingFeature.State.HALF_OPENED -> NativeFoldState.HALF_OPENED
          FoldingFeature.State.FLAT -> NativeFoldState.FLAT
          else -> NativeFoldState.UNKNOWN
        },
        orientation = when (it.orientation) {
          FoldingFeature.Orientation.VERTICAL -> NativeFoldOrientation.VERTICAL
          FoldingFeature.Orientation.HORIZONTAL -> NativeFoldOrientation.HORIZONTAL
          else -> NativeFoldOrientation.UNKNOWN
        },
        boundsPx = FoldBoundsPx(bounds.left, bounds.top, bounds.right, bounds.bottom),
        rootOriginPx = RootOriginPx(metrics.x, metrics.y),
        density = metrics.density,
        isSeparating = it.isSeparating,
        isFullyOccluding = it.occlusionType == FoldingFeature.OcclusionType.FULL,
      )
    }
    coordinator.onLayout(target.generation, sample)
  }

  private fun metrics(root: View): Metrics {
    val origin = IntArray(2)
    root.getLocationInWindow(origin)
    return Metrics(origin[0], origin[1], root.resources.displayMetrics.density, root.width, root.height)
  }

  private fun detachHost() {
    val previous = host
    host = null
    latestLayout = null
    lastMetrics = null
    targets.value = null
    if (previous != null) {
      previous.root.removeOnAttachStateChangeListener(attachmentListener)
      coordinator.detachHost(previous.generation)
    }
  }

  private fun removeDecorObserver() {
    observedTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
    observedTree = null
    decor = null
  }

  private fun onMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else {
      check(main.post(action)) { "Unable to schedule Clamshell host lifecycle work" }
    }
  }

  private fun withOpen(action: () -> Unit) = onMain {
    synchronized(gate) {
      if (!closed) action()
    }
  }

  override fun close() {
    synchronized(gate) {
      if (closed) return
      closed = true
      scope.cancel()
      context.removeLifecycleEventListener(this)
    }
    onMain {
      synchronized(gate) {
        host?.root?.removeOnAttachStateChangeListener(attachmentListener)
        host = null
        targets.value = null
        latestLayout = null
        lastMetrics = null
        activity = null
        removeDecorObserver()
      }
    }
  }

  companion object {
    private const val TAG = "Clamshell"

    private fun findReactRoot(view: View, context: ReactApplicationContext): View? {
      if (view is ReactRootView && view.currentReactContext === context) return view
      if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
          findReactRoot(view.getChildAt(index), context)?.let { return it }
        }
      }
      return null
    }
  }
}
