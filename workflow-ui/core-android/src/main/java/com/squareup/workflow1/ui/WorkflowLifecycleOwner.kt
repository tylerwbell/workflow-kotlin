package com.squareup.workflow1.ui

import android.view.View
import android.view.View.OnAttachStateChangeListener
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.PRIVATE
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event
import androidx.lifecycle.Lifecycle.State.CREATED
import androidx.lifecycle.Lifecycle.State.DESTROYED
import androidx.lifecycle.Lifecycle.State.INITIALIZED
import androidx.lifecycle.Lifecycle.State.RESUMED
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleRegistry.createUnsafe
import androidx.lifecycle.ViewTreeLifecycleOwner
import com.squareup.workflow1.ui.WorkflowAndroidXSupport.lifecycleOwnerFromViewTreeOrContext
import com.squareup.workflow1.ui.WorkflowLifecycleOwner.Companion.get
import com.squareup.workflow1.ui.WorkflowLifecycleOwner.Companion.installOn

/**
 * An extension of [LifecycleOwner] that is always owned by a [View], is logically a child lifecycle
 * of the next-nearest [ViewTreeLifecycleOwner] above it (it mirrors its parent's lifecycle until
 * it's destroyed), and can be [asked to destroy][destroyOnDetach] itself early.
 *
 * This type is meant to help integrate with [ViewTreeLifecycleOwner] by allowing the creation of a
 * tree of [LifecycleOwner]s that mirrors the view tree.
 *
 * Custom container views that use [ViewRegistry.buildView] to create their children _must_ ensure
 * they call [destroyOnDetach] on the outgoing view before they replace children with new views.
 * If this is not done, then certain processes that are started by that view's subtree may continue
 * to run long after the view is been detached, and memory and other resources may be leaked.
 *
 * Set a [WorkflowLifecycleOwner] on a view by calling [installOn], and read it back using [get].
 */
@WorkflowUiExperimentalApi
public interface WorkflowLifecycleOwner : LifecycleOwner {

  /**
   * If the owning view is attached, flags this [lifecycle][Lifecycle] to be set to [DESTROYED] as
   * soon as the owning view is [detached][View.onDetachedFromWindow]. If the view is not attached
   * (either because it's never been attached, or because it was attached and then detached), then
   * it will destroy the lifecycle immediately.
   */
  public fun destroyOnDetach()

  public companion object {
    /**
     * Creates a new [WorkflowLifecycleOwner] and sets it on [view]'s tags so it can be later
     * retrieved with [get].
     *
     * It's very important that, once this function is called with a given view, that EITHER:
     *
     * 1. The view gets attached at least once to ensure that the lifecycle eventually gets
     *    destroyed (because its parent is destroyed), or
     * 2. Someone eventually calls [destroyOnDetach], which will either schedule the lifecycle to
     *    destroyed if the view is attached, or destroy it immediately if it's detached.
     *
     * If this is not done, any observers registered with the [Lifecycle] may be leaked as they will
     * never see the destroy event.
     *
     * @param findParentLifecycle A function that is called whenever [view] is attached, and should
     * return the [Lifecycle] to use as the parent lifecycle. If not specified, defaults to looking
     * up the view tree by calling [ViewTreeLifecycleOwner.get] on [view]'s parent, and if none is
     * found, then looking up [view]'s context wrapper chain for something that implements
     * [LifecycleOwner]. This only needs to be passed if [view] will be used as the root of a new
     * view hierarchy, e.g. for a new dialog. If no parent lifecycle is found, then the lifecycle
     * will become [RESUMED] when it's attached for the first time, and stay in that state until
     * it is re-attached with a non-null parent or [destroyOnDetach] is called.
     */
    public fun installOn(
      view: View,
      findParentLifecycle: () -> Lifecycle? = { findViewTreeLifecycle(view) }
    ) {
      RealWorkflowLifecycleOwner(view, findParentLifecycle).also {
        ViewTreeLifecycleOwner.set(view, it)
        view.addOnAttachStateChangeListener(it)
      }
    }

    /**
     * Looks for the nearest [ViewTreeLifecycleOwner] on [view] and returns it if it's an instance
     * of [WorkflowLifecycleOwner]. Convenience function for retrieving the owner set by
     * [installOn].
     */
    public fun get(view: View): WorkflowLifecycleOwner? =
      ViewTreeLifecycleOwner.get(view) as? WorkflowLifecycleOwner

    private fun findViewTreeLifecycle(view: View): Lifecycle? {
      // Start at our view's parent – if we look on our own view, we'll just get this back.
      return (view.parent as? View)?.let(::lifecycleOwnerFromViewTreeOrContext)?.lifecycle
    }
  }
}

/**
 * @param enforceMainThread Allows disabling the main thread check for testing.
 */
@OptIn(WorkflowUiExperimentalApi::class)
@VisibleForTesting(otherwise = PRIVATE)
internal class RealWorkflowLifecycleOwner(
  private val view: View,
  private val findParentLifecycle: () -> Lifecycle?,
  enforceMainThread: Boolean = true,
) : WorkflowLifecycleOwner,
  LifecycleOwner,
  OnAttachStateChangeListener,
  LifecycleEventObserver {

  private val localLifecycle =
    if (enforceMainThread) LifecycleRegistry(this) else createUnsafe(this)

  /**
   * The parent lifecycle found by calling [ViewTreeLifecycleOwner.get] on the owning view's parent
   * (once it's attached), or if no [ViewTreeLifecycleOwner] is set, then by trying to find a
   * [LifecycleOwner] on the view's context.
   *
   * When the view is detached, we keep the reference to, and observing, the previous parent
   * lifecycle, to ensure we get destroyed correctly if the parent is destroyed while we're
   * detached. The next time we're attached, we search for a parent again, in case we're attached
   * in a different subtree that has a different parent.
   *
   * This is only null in two cases:
   * 1. The view hasn't been attached yet, ever.
   * 2. The lifecycle has been destroyed.
   */
  private var parentLifecycle: Lifecycle? = null
  private var destroyOnDetach = false

  override fun onViewAttachedToWindow(v: View) {
    // Always check for a new parent, in case we're attached to different part of the view tree.
    val oldLifecycle = parentLifecycle
    parentLifecycle = checkNotNull(findParentLifecycle()) {
      "Expected to find either a ViewTreeLifecycleOwner in the view tree, or for the view's" +
        " context to be a LifecycleOwner."
    }

    if (parentLifecycle !== oldLifecycle) {
      oldLifecycle?.removeObserver(this)
      parentLifecycle?.addObserver(this)
    }
    updateLifecycle(isAttached = true)
  }

  override fun onViewDetachedFromWindow(v: View) {
    updateLifecycle(isAttached = false)
  }

  /** Called when the [parentLifecycle] changes state. */
  override fun onStateChanged(
    source: LifecycleOwner,
    event: Event
  ) {
    updateLifecycle()
  }

  override fun destroyOnDetach() {
    if (!destroyOnDetach) {
      destroyOnDetach = true
      updateLifecycle()
    }
  }

  override fun getLifecycle(): Lifecycle = localLifecycle

  /**
   * @param isAttached Whether the view is [attached][View.isAttachedToWindow]. Must be passed
   * explicitly when called from the attach/detach callbacks, since the view property's value won't
   * reflect the new state until after they return.
   */
  @VisibleForTesting(otherwise = PRIVATE)
  internal fun updateLifecycle(isAttached: Boolean = view.isAttachedToWindow) {
    val parentState = parentLifecycle?.currentState
    val localState = localLifecycle.currentState

    if (localState == DESTROYED) {
      // Local destruction is a terminal state.
      return
    }

    localLifecycle.currentState = when {
      // We've been queued for destruction.
      // Stay attached to the parent's lifecycle until we re-attach, since the parent could be
      // destroyed while we're detached.
      destroyOnDetach && !isAttached -> DESTROYED
      // We may or may not be attached, but we have a parent lifecycle so we just blindly follow it.
      parentState != null -> parentState
      // We have no parent and we're not destroyed, which means we have never been attached, so the
      // only valid state we can be in is INITIALIZED.
      localState == INITIALIZED -> localState
      // We don't have a parent and we're neither in DESTROYED or INITIALIZED: this is an invalid
      // state. Throw an AssertionError instead of IllegalStateException because there's no API to
      // get into this state, so this means the library has a bug.
      else -> {
        throw AssertionError(
          "Must have a parent lifecycle after attaching and until being destroyed."
        )
      }
    }.also { newState ->
      if (newState == DESTROYED) {
        // We're now in a terminal DESTROY state. Be a good citizen and make sure to detach from
        // our parent.
        parentLifecycle?.removeObserver(this)
        parentLifecycle = null

        if (localLifecycle.currentState == INITIALIZED) {
          // We're being asked to destroy before we've ever attached. LifecycleRegistry throws if
          // you try moving an observer (only happens if there is at least one uninitialized
          // observer) from INITIALIZED to DESTROYED, so we need to fake it.
          // This path is probably only ever hit in unit tests, but is probably the behavior we want
          // anyway in case we're self-observing.
          localLifecycle.currentState = CREATED
        }
      }
    }
  }
}
