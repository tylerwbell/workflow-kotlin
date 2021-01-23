package com.squareup.workflow1.ui.internal.test

import android.os.Bundle
import android.view.View
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import com.google.common.truth.Truth.assertThat
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import kotlin.test.fail

/**
 * Helper class for testing interactions with a [SavedStateRegistry].
 *
 * Write values to [statesToSaveByName] before asking the registry to save, and later check
 * [restoredStatesByName] to see if the values were restored that you were expecting.
 */
@WorkflowUiExperimentalApi
public class StateRegistryTestHelper {
  public val statesToSaveByName: MutableMap<String, String> = mutableMapOf()
  public val restoredStatesByName: MutableMap<String, String?> = mutableMapOf()

  public fun initialize(activity: AbstractLifecycleTestActivity) {
    activity.onViewAttachStateChangedListener = { view, rendering, attached ->
      val registry = view.requireStateRegistry()
      val key = rendering.compatibilityKey

      if (attached) {
        assertThat(restoredStatesByName).doesNotContainKey(key)
        registry.consumeRestoredStateForKey(key)?.let { restoredBundle ->
          assertThat(restoredBundle.getString("rendering.name")).isEqualTo(key)
          restoredStatesByName[key] = restoredBundle.getString("state")
        }

        registry.registerSavedStateProvider(key) {
          Bundle().apply {
            putString("rendering.name", key)
            statesToSaveByName[key]?.let { state ->
              putString("state", state)
            }
          }
        }
      } else {
        registry.unregisterSavedStateProvider(key)
      }
    }
  }
}

public fun View.requireStateRegistry(): SavedStateRegistry =
  ViewTreeSavedStateRegistryOwner.get(this)?.savedStateRegistry
    ?: fail("Expected ViewTreeSavedStateRegistryOwner to be set on view.")
