package com.squareup.workflow1.ui.modal

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import com.squareup.workflow1.ui.WorkflowAndroidXSupport.savedStateRegistryOwnerFromViewTreeOrContext
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi

/**
 * "Forwards" any view tree tags from the [anchorView]'s view tree to each of the [rootViews].
 *
 * Must only be called when [anchorView] is attached, and should be called every time it's attached
 * in case any of the owners will resolve to different values.
 *
 * Forwarding is best-effort – if no owner is set, none will be forwarded.
 *
 * @param anchorView A [View] that is attached and exists in the "main" view tree. E.g. a modal
 * container view. View tree owners will be read from this view.
 * @param rootViews A [Sequence] of [View]s that will be shown, but will not be part of the same
 * view tree as [anchorView]. E.g. the root views for some dialogs that are managed by [anchorView].
 * View tree owners from [anchorView] will be set on all these views.
 */
@OptIn(WorkflowUiExperimentalApi::class)
internal fun linkViewTreeOwners(
  anchorView: View,
  rootViews: Sequence<View>
) {
  val context = anchorView.context
  val parentSavedStateRegistryOwner = savedStateRegistryOwnerFromViewTreeOrContext(anchorView)
  val parentViewModelStore = ViewTreeViewModelStoreOwner.get(anchorView)
    ?: context?.viewModelStoreOwnerOrNull()

  rootViews.forEach { rootView ->
    // Forward the SavedStateRegistryOwner.
    parentSavedStateRegistryOwner?.let {
      ViewTreeSavedStateRegistryOwner.set(rootView, object : SavedStateRegistryOwner {
        override fun getLifecycle(): Lifecycle = it.lifecycle
        override fun getSavedStateRegistry(): SavedStateRegistry = it.savedStateRegistry
      })
    }

    // Forward the ViewModelStoreOwner.
    parentViewModelStore?.let {
      ViewTreeViewModelStoreOwner.set(rootView, it)
    }
  }
}

/**
 * The [ViewModelStoreOwner] for this context, or null if one can't be found.
 */
private tailrec fun Context.viewModelStoreOwnerOrNull(): ViewModelStoreOwner? = when (this) {
  is ViewModelStoreOwner -> this
  else -> (this as? ContextWrapper)?.baseContext?.viewModelStoreOwnerOrNull()
}
