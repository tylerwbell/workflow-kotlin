package com.squareup.workflow1.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner

/**
 * Namespace for some helper functions for interacting with the AndroidX libraries.
 */
public object WorkflowAndroidXSupport {

  /**
   * Tries to get the parent lifecycle from the current view via [ViewTreeLifecycleOwner], if that
   * fails it looks up the context chain for a [LifecycleOwner], and if that fails it just returns
   *  null.
   */
  @WorkflowUiExperimentalApi
  public fun lifecycleOwnerFromViewTreeOrContext(view: View): LifecycleOwner? =
    ViewTreeLifecycleOwner.get(view) ?: view.context.lifecycleOwnerOrNull()

  /**
   * Tries to get the parent [SavedStateRegistryOwner] from the current view's view via
   * [ViewTreeSavedStateRegistryOwner], if that fails it looks up the context chain for a one,
   * and if that fails it just returns null.
   */
  @WorkflowUiExperimentalApi
  public fun savedStateRegistryOwnerFromViewTreeOrContext(view: View): SavedStateRegistryOwner? =
    ViewTreeSavedStateRegistryOwner.get(view) ?: view.context.savedStateRegistryOwnerOrNull()

  private tailrec fun Context.lifecycleOwnerOrNull(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    else -> (this as? ContextWrapper)?.baseContext?.lifecycleOwnerOrNull()
  }

  private tailrec fun Context.savedStateRegistryOwnerOrNull(): SavedStateRegistryOwner? =
    when (this) {
      is SavedStateRegistryOwner -> this
      else -> (this as? ContextWrapper)?.baseContext?.savedStateRegistryOwnerOrNull()
    }
}
