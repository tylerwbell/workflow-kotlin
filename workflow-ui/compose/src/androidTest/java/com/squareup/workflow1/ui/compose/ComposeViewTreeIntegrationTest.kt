package com.squareup.workflow1.ui.compose

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
import androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import com.squareup.workflow1.ui.AndroidViewRendering
import com.squareup.workflow1.ui.Compatible
import com.squareup.workflow1.ui.NamedViewFactory
import com.squareup.workflow1.ui.ViewEnvironment
import com.squareup.workflow1.ui.ViewFactory
import com.squareup.workflow1.ui.ViewRegistry
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import com.squareup.workflow1.ui.backstack.BackStackScreen
import com.squareup.workflow1.ui.bindShowRendering
import com.squareup.workflow1.ui.internal.test.WorkflowUiTestActivity
import com.squareup.workflow1.ui.modal.HasModals
import com.squareup.workflow1.ui.modal.ModalViewContainer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.reflect.KClass

@OptIn(WorkflowUiExperimentalApi::class)
internal class ComposeViewTreeIntegrationTest {

  @Rule @JvmField val composeRule = createAndroidComposeRule<WorkflowUiTestActivity>()
  private val scenario get() = composeRule.activityRule.scenario

  @Before fun setUp() {
    scenario.onActivity {
      it.viewEnvironment = ViewEnvironment(
        mapOf(
          ViewRegistry to ViewRegistry(
            NoTransitionBackStackContainer,
            NamedViewFactory,
            ModalViewContainer.binding<TestModals>()
          )
        )
      )
    }
  }

  @Test fun compose_view_assertions_work() {
    val firstScreen = ComposeRendering("first") {
      BasicText("First Screen")
    }
    val secondScreen = ComposeRendering("second") {}

    scenario.onActivity {
      it.setBackstack(firstScreen)
    }

    composeRule.onNodeWithText("First Screen").assertIsDisplayed()

    // Navigate away from the first screen.
    scenario.onActivity {
      it.setBackstack(firstScreen, secondScreen)
    }

    composeRule.onNodeWithText("First Screen").assertDoesNotExist()
  }

  @Test fun composition_is_disposed_when_navigated_away_dispose_on_detach_strategy() {
    var composedCount = 0
    var disposedCount = 0
    val firstScreen = ComposeRendering("first", disposeStrategy = DisposeOnDetachedFromWindow) {
      DisposableEffect(Unit) {
        composedCount++
        onDispose {
          disposedCount++
        }
      }
    }
    val secondScreen = ComposeRendering("second") {}

    scenario.onActivity {
      it.setBackstack(firstScreen)
    }

    assertThat(composedCount).isEqualTo(1)
    assertThat(disposedCount).isEqualTo(0)

    // Navigate away.
    scenario.onActivity {
      it.setBackstack(firstScreen, secondScreen)
    }

    assertThat(composedCount).isEqualTo(1)
    assertThat(disposedCount).isEqualTo(1)
  }

  @Test fun composition_is_disposed_when_navigated_away_dispose_on_destroy_strategy() {
    var composedCount = 0
    var disposedCount = 0
    val firstScreen =
      ComposeRendering("first", disposeStrategy = DisposeOnViewTreeLifecycleDestroyed) {
        DisposableEffect(Unit) {
          composedCount++
          onDispose {
            disposedCount++
          }
        }
      }
    val secondScreen = ComposeRendering("second") {}

    scenario.onActivity {
      it.setBackstack(firstScreen)
    }

    assertThat(composedCount).isEqualTo(1)
    assertThat(disposedCount).isEqualTo(0)

    // Navigate away.
    scenario.onActivity {
      it.setBackstack(firstScreen, secondScreen)
    }

    assertThat(composedCount).isEqualTo(1)
    assertThat(disposedCount).isEqualTo(1)
  }

  @Test fun composition_state_is_restored_after_config_change() {
    var state: MutableState<String>? = null
    val firstScreen = ComposeRendering("first") {
      val innerState = rememberSaveable { mutableStateOf("hello world") }
      DisposableEffect(Unit) {
        state = innerState
        onDispose { state = null }
      }
    }

    // Show first screen to initialize state.
    scenario.onActivity {
      it.setBackstack(firstScreen)
    }
    composeRule.runOnIdle {
      assertThat(state!!.value).isEqualTo("hello world")
    }
    state!!.value = "saved"

    // Simulate config change.
    scenario.recreate()

    composeRule.runOnIdle {
      assertThat(state!!.value).isEqualTo("saved")
    }
  }

  // region merging

  @Test fun composition_state_is_restored_on_pop() {
    var state: MutableState<String>? = null
    val firstScreen =
      ComposeRendering("first") {
        val innerState = savedInstanceState { "hello world" }
        DisposableEffect(Unit) {
          state = innerState
          onDispose { state = null }
        }
      }
    val secondScreen =
      ComposeRendering("second") {}

    // Show first screen to initialize state.
    scenario.onActivity {
      it.setBackstack(firstScreen)
    }
    composeRule.runOnIdle {
      assertThat(state!!.value).isEqualTo("hello world")
    }
    state!!.value = "saved"

    // Navigate away.
    scenario.onActivity {
      it.setBackstack(firstScreen, secondScreen)
    }
    composeRule.runOnIdle {
      assertThat(state).isNull()
    }

    // Navigate back to restore state.
    scenario.onActivity {
      it.setBackstack(firstScreen)
    }

    composeRule.runOnIdle {
      assertThat(state!!.value).isEqualTo("saved")
    }
  }

  @Test fun composition_state_is_restored_on_pop_after_config_change() {
    var state: MutableState<String>? = null
    val firstScreen =
      ComposeRendering("first") {
        val innerState = savedInstanceState { "hello world" }
        DisposableEffect(Unit) {
          state = innerState
          onDispose { state = null }
        }
      }
    val secondScreen = ComposeRendering("second") {}

    // Show first screen to initialize state.
    scenario.onActivity {
      it.setBackstack(firstScreen)
    }
    composeRule.runOnIdle {
      assertThat(state!!.value).isEqualTo("hello world")
    }
    state!!.value = "saved"

    // Navigate away.
    scenario.onActivity {
      it.setBackstack(firstScreen, secondScreen)
    }
    composeRule.runOnIdle {
      assertThat(state).isNull()
    }

    // Simulate config change.
    scenario.recreate()

    // Navigate back to restore state.
    scenario.onActivity {
      it.setBackstack(firstScreen)
    }

    composeRule.runOnIdle {
      assertThat(state!!.value).isEqualTo("saved")
    }
  }

  @Test fun composition_state_is_restored_in_modal_after_config_change() {
    var state: MutableState<String>? = null
    val firstScreen = ComposeRendering("first") {
      val innerState = savedInstanceState { "hello world" }
      DisposableEffect(Unit) {
        state = innerState
        onDispose { state = null }
      }
    }

    // Show first screen to initialize state.
    scenario.onActivity {
      it.setModals(firstScreen)
    }
    composeRule.runOnIdle {
      assertThat(state!!.value).isEqualTo("hello world")
    }
    state!!.value = "saved"

    // Simulate config change.
    scenario.recreate()

    composeRule.runOnIdle {
      assertThat(state!!.value).isEqualTo("saved")
    }
  }

  // endregion

  private fun WorkflowUiTestActivity.setBackstack(vararg backstack: ComposeRendering) {
    setRendering(BackStackScreen(EmptyRendering, backstack.asList()))
  }

  private fun WorkflowUiTestActivity.setModals(vararg modals: ComposeRendering) {
    setRendering(TestModals(modals.asList()))
  }

  data class ComposeRendering(
    override val compatibilityKey: String,
    val disposeStrategy: ViewCompositionStrategy? = null,
    val content: @Composable () -> Unit
  ) : Compatible, AndroidViewRendering<ComposeRendering>, ViewFactory<ComposeRendering> {
    override val type: KClass<in ComposeRendering> = ComposeRendering::class
    override val viewFactory: ViewFactory<ComposeRendering> get() = this

    override fun buildView(
      initialRendering: ComposeRendering,
      initialViewEnvironment: ViewEnvironment,
      contextForNewView: Context,
      container: ViewGroup?
    ): View {
      var lastCompositionStrategy = initialRendering.disposeStrategy

      return ComposeView(contextForNewView).apply {
        lastCompositionStrategy?.let(::setViewCompositionStrategy)

        // Need to set the hash code for persistence.
        id = initialRendering.compatibilityKey.hashCode()

        bindShowRendering(initialRendering, initialViewEnvironment) { rendering, _ ->
          if (rendering.disposeStrategy != lastCompositionStrategy) {
            lastCompositionStrategy = rendering.disposeStrategy
            lastCompositionStrategy?.let(::setViewCompositionStrategy)
          }

          setContent(rendering.content)
        }
      }
    }
  }

  private data class TestModals(
    override val modals: List<ComposeRendering>
  ) : HasModals<ComposeRendering, ComposeRendering> {
    override val beneathModals: ComposeRendering get() = EmptyRendering
  }

  companion object {
    // Use a ComposeView here because the Compose test infra doesn't like it if there are no
    // Compose views at all. See https://issuetracker.google.com/issues/179455327.
    val EmptyRendering = ComposeRendering(compatibilityKey = "") {}
  }
}
