package com.squareup.workflow1.ui.internal.test

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle.Event
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import com.squareup.workflow1.ui.BuilderViewFactory
import com.squareup.workflow1.ui.Compatible
import com.squareup.workflow1.ui.NamedViewFactory
import com.squareup.workflow1.ui.ViewEnvironment
import com.squareup.workflow1.ui.ViewFactory
import com.squareup.workflow1.ui.ViewRegistry
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import com.squareup.workflow1.ui.WorkflowViewStub
import com.squareup.workflow1.ui.bindShowRendering
import com.squareup.workflow1.ui.plus
import kotlin.reflect.KClass

@WorkflowUiExperimentalApi
internal typealias AttachStateListener = (View, Compatible, attached: Boolean) -> Unit

/**
 * Base activity class to help test container view implementations' [LifecycleOwner] behaviors.
 *
 * Create an `ActivityScenarioRule` in your test that launches your subclass of this activity, and
 * then have your subclass expose a method that calls [setRendering] with whatever rendering type your
 * test wants to use. Then call [consumeLifecycleEvents] to get a list of strings back that describe
 * what lifecycle-related events occurred since the last call.
 *
 * Subclasses must override [viewRegistry] to specify the [ViewFactory]s they require. All views
 * will be hosted inside a [WorkflowViewStub].
 */
@WorkflowUiExperimentalApi
public abstract class AbstractLifecycleTestActivity : WorkflowUiTestActivity() {

  private val lifecycleEvents = mutableListOf<String>()

  protected abstract val viewRegistry: ViewRegistry

  /**
   * Called whenever a test view is attached or detached.
   *
   * If non-null, this instance will be preserved across configuration changes. Be careful not to
   * capture the activity.
   */
  public var onViewAttachStateChangedListener: AttachStateListener? by customNonConfigurationData
    .withDefault { null }

  /**
   * Returns a list of strings describing what lifecycle-related events occurred since the last
   * call to this method. Use this list to validate the ordering of lifecycle events in your tests.
   *
   * Hint: Start by expecting this list to be empty, then copy-paste the actual strings from the
   * test failure into your test and making sure they look reasonable.
   */
  public fun consumeLifecycleEvents(): List<String> = lifecycleEvents.toList().also {
    lifecycleEvents.clear()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    logEvent("activity onCreate")

    // This will override WorkflowUiTestActivity's retention of the environment across config
    // changes. This is intentional, since our ViewRegistry probably contains a leafBinding which
    // captures the events list.
    viewEnvironment = ViewEnvironment(mapOf(ViewRegistry to viewRegistry + NamedViewFactory))

    // customNonConfigurationData["onAttachListener"]?.let
    // TODO
    // // Must set attached listener first so that it will be invoked when update sets the view.
    // onViewAttachStateChangedListener = it.attachedStateListener
  }

  override fun onStart() {
    super.onStart()
    logEvent("activity onStart")
  }

  override fun onResume() {
    super.onResume()
    logEvent("activity onResume")
  }

  override fun onPause() {
    logEvent("activity onPause")
    super.onPause()
  }

  override fun onStop() {
    logEvent("activity onStop")
    super.onStop()
  }

  override fun onDestroy() {
    logEvent("activity onDestroy")
    super.onDestroy()
  }

  protected fun logEvent(message: String) {
    lifecycleEvents += message
  }

  protected fun <R : Compatible> leafViewBinding(
    type: KClass<R>,
    viewObserver: ViewObserver<R>,
    viewConstructor: (Context) -> LeafView<R> = ::LeafView
  ): ViewFactory<R> =
    BuilderViewFactory(type) { initialRendering, initialViewEnvironment, contextForNewView, _ ->
      viewConstructor(contextForNewView).apply {
        this.viewObserver = viewObserver
        this.attachListener = this@AbstractLifecycleTestActivity.onViewAttachStateChangedListener
        viewObserver.onViewCreated(this, initialRendering)

        bindShowRendering(initialRendering, initialViewEnvironment) { rendering, _ ->
          this.rendering = rendering
          viewObserver.onShowRendering(this, rendering)
        }
      }
    }

  protected fun <R : Compatible> lifecycleLoggingViewObserver(): ViewObserver<R> =
    object : ViewObserver<R> {
      override fun onAttachedToWindow(
        view: View,
        rendering: R
      ) {
        logEvent("LeafView ${rendering.compatibilityKey} onAttached")
      }

      override fun onDetachedFromWindow(
        view: View,
        rendering: R
      ) {
        logEvent("LeafView ${rendering.compatibilityKey} onDetached")
      }

      override fun onViewTreeLifecycleStateChanged(
        rendering: R,
        event: Event
      ) {
        logEvent("LeafView ${rendering.compatibilityKey} $event")
      }
  }

  public interface ViewObserver<R : Any> {
    public fun onViewCreated(
      view: View,
      rendering: R
    ) {
    }

    public fun onShowRendering(
      view: View,
      rendering: R
    ) {
    }

    public fun onAttachedToWindow(
      view: View,
      rendering: R
    ) {
    }

    public fun onDetachedFromWindow(
      view: View,
      rendering: R
    ) {
    }

    public fun onViewTreeLifecycleStateChanged(
      rendering: R,
      event: Event
    ) {
    }

    public fun onSaveInstanceState(
      view: View,
      rendering: R
    ) {
    }

    public fun onRestoreInstanceState(
      view: View,
      rendering: R
    ) {
    }
  }

  public open class LeafView<R : Compatible>(
    context: Context
  ) : FrameLayout(context) {

    internal var viewObserver: ViewObserver<R>? = null
    internal var attachListener: AttachStateListener? = null

    // We can't rely on getRendering() in case it's wrapped with Named.
    public lateinit var rendering: R
      internal set

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
      viewObserver?.onViewTreeLifecycleStateChanged(rendering, event)
    }

    override fun onAttachedToWindow() {
      super.onAttachedToWindow()
      viewObserver?.onAttachedToWindow(this, rendering)
      attachListener?.invoke(this, rendering, true)

      ViewTreeLifecycleOwner.get(this)!!.lifecycle.removeObserver(lifecycleObserver)
      ViewTreeLifecycleOwner.get(this)!!.lifecycle.addObserver(lifecycleObserver)
    }

    override fun onDetachedFromWindow() {
      // Don't remove the lifecycle observer here, since we need to observe events after detach.
      attachListener?.invoke(this, rendering, false)
      viewObserver?.onDetachedFromWindow(this, rendering)
      super.onDetachedFromWindow()
    }

    override fun onSaveInstanceState(): Parcelable? {
      return super.onSaveInstanceState().apply {
        viewObserver?.onSaveInstanceState(this@LeafView, rendering)
      }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
      super.onRestoreInstanceState(state)
      viewObserver?.onRestoreInstanceState(this@LeafView, rendering)
    }
  }
}
