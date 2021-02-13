package com.squareup.workflow1.ui.modal

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event
import androidx.lifecycle.Lifecycle.Event.ON_CREATE
import androidx.lifecycle.Lifecycle.Event.ON_DESTROY
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import androidx.savedstate.SavedStateRegistry
import com.squareup.workflow1.ui.Named
import com.squareup.workflow1.ui.ViewEnvironment
import com.squareup.workflow1.ui.ViewStateFrame
import com.squareup.workflow1.ui.WorkflowAndroidXSupport.compositeViewIdKey
import com.squareup.workflow1.ui.WorkflowAndroidXSupport.lifecycleOwnerFromViewTreeOrContext
import com.squareup.workflow1.ui.WorkflowAndroidXSupport.savedStateRegistryOwnerFromViewTreeOrContext
import com.squareup.workflow1.ui.WorkflowLifecycleOwner
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import com.squareup.workflow1.ui.WorkflowViewStub
import com.squareup.workflow1.ui.compatible
import kotlin.LazyThreadSafetyMode.NONE

/**
 * Base class for containers that show [HasModals.modals] in [Dialog] windows.
 *
 * @param ModalRenderingT the type of the nested renderings to be shown in a dialog window.
 */
@WorkflowUiExperimentalApi
public abstract class ModalContainer<ModalRenderingT : Any> @JvmOverloads constructor(
  context: Context,
  attributeSet: AttributeSet? = null,
  defStyle: Int = 0,
  defStyleRes: Int = 0
) : FrameLayout(context, attributeSet, defStyle, defStyleRes) {

  /**
   * Wrap the [baseViewStub] in another view, since [WorkflowViewStub] swaps itself out for its
   * child view, but we need a stable reference to its view subtree to set up ViewTreeOwners.
   * Wrapping with a container view is simpler than trying to make always make sure we keep
   * [baseViewStub]'s [actual][WorkflowViewStub.actual] view updated.
   *
   * Note that this isn't a general problem that needs to be solved for WVS, it's only because we're
   * using it as a direct child of a container. This can't happen through the ViewRegistry since
   * no view factories should ever return a WVS.
   */
  private val baseContainer = FrameLayout(context).also {
    // We need to install here so that the baseStateFrame can read it.
    // We never need to call destroyOnDetach for this owner though, since we never replace it and
    // so the only way it can be destroyed is if our parent lifecycle gets destroyed.
    WorkflowLifecycleOwner.installOn(it)
    addView(it, LayoutParams(MATCH_PARENT, MATCH_PARENT))
  }

  private val baseViewStub: WorkflowViewStub = WorkflowViewStub(context).also {
    baseContainer.addView(it, LayoutParams(MATCH_PARENT, MATCH_PARENT))
  }

  private var dialogs: List<DialogRef<ModalRenderingT>> = emptyList()
  private val parentLifecycleOwner by lazy(NONE) { WorkflowLifecycleOwner.get(this) }

  private val baseStateFrame = ViewStateFrame("base").also {
    it.attach(baseContainer)
  }

  private var isRestored = false

  protected fun update(
    newScreen: HasModals<*, ModalRenderingT>,
    viewEnvironment: ViewEnvironment
  ) {
    baseViewStub.update(newScreen.beneathModals, viewEnvironment)

    val newDialogs = mutableListOf<DialogRef<ModalRenderingT>>()
    for ((i, modal) in newScreen.modals.withIndex()) {
      newDialogs += if (i < dialogs.size && compatible(dialogs[i].modalRendering, modal)) {
        dialogs[i].withUpdate(modal, viewEnvironment)
          .also {
            updateDialog(it)
          }
      } else {
        buildDialog(modal, viewEnvironment).also { ref ->
          ref.dialog.decorView?.let { dialogView ->
            // Implementations of buildDialog may use ViewRegistry.buildView, which will set the
            // WorkflowLifecycleOwner on the content view, but since we can't rely on that we also
            // set it here. When the views are attached, this will become the parent lifecycle of
            // the one from buildDialog if any, and so we can use our lifecycle to destroy-on-detach
            // the dialog hierarchy.
            WorkflowLifecycleOwner.installOn(
              dialogView,
              findParentLifecycle = { parentLifecycleOwner?.lifecycle }
            )

            ref.initFrame()
            // This must be done after installing the WLO, because it assumes that the WLO has
            // already been installed.
            ref.frame.attach(dialogView)
            if (isRestored) {
              // Need to initialize the saved state registry even though we're not actually
              // restoring. However if we haven't been restored yet, then this call will be made
              // by restoreFromBundle.
              ref.frame.restoreAndroidXStateRegistry()
            }

            dialogView.addOnAttachStateChangeListener(
              object : OnAttachStateChangeListener {
                val onDestroy = OnDestroy { ref.dismiss() }
                var lifecycle: Lifecycle? = null
                override fun onViewAttachedToWindow(v: View) {
                  println("OMG MC dialog attached to window")
                  dumpState()

                  // Note this is a different lifecycle than the WorkflowLifecycleOwner – it will
                  // probably be the owning AppCompatActivity.
                  lifecycle = ref.dialog.decorView
                    ?.let(::lifecycleOwnerFromViewTreeOrContext)
                    ?.lifecycle
                  // Android makes a lot of logcat noise if it has to close the window for us. :/
                  // https://github.com/square/workflow/issues/51
                  lifecycle?.addObserver(onDestroy)
                }

                override fun onViewDetachedFromWindow(v: View) {
                  println("OMG MC dialog detached from window")
                  lifecycle?.removeObserver(onDestroy)
                  lifecycle = null
                }
              }
            )
          }
          ref.dialog.show()
        }
      }
    }

    linkModalViewTreeOwners(newDialogs)

    (dialogs - newDialogs).forEach { it.dismiss() }
    dialogs = newDialogs

    println("OMG MC finished update")
    dumpState()
  }

  @OptIn(ExperimentalStdlibApi::class)
  private fun dumpState() {
    println("OMG MC state:")
    val lines = buildList {
      add("${baseStateFrame.key}: $baseStateFrame")
      dialogs.forEach {
        add("${it.frame.key}: ${it.frame}")
      }
    }
    println(lines.joinToString(separator = "\n") { "OMG   $it" })
  }

  /**
   * Called to create (but not show) a Dialog to render [initialModalRendering].
   */
  protected abstract fun buildDialog(
    initialModalRendering: ModalRenderingT,
    initialViewEnvironment: ViewEnvironment
  ): DialogRef<ModalRenderingT>

  protected abstract fun updateDialog(dialogRef: DialogRef<ModalRenderingT>)

  private val stateRegistryKey by lazy(NONE) {
    val stateRegistryPrefix = compositeViewIdKey(this)
    "$stateRegistryPrefix/${ModalContainer::class.java.simpleName}"
  }
  private lateinit var parentStateRegistry: SavedStateRegistry

  override fun onAttachedToWindow() {
    // TODO more graceful null handling
    val registryOwner = savedStateRegistryOwnerFromViewTreeOrContext(this)!!
    parentStateRegistry = registryOwner.savedStateRegistry
    parentStateRegistry.registerSavedStateProvider(stateRegistryKey, ::saveToBundle)

    registryOwner.lifecycle.addObserver(object : LifecycleEventObserver {
      override fun onStateChanged(
        source: LifecycleOwner,
        event: Event
      ) {
        println("OMG MC got event: $event")
        if (event == ON_CREATE) {
          // source.lifecycle.removeObserver(this)
          parentStateRegistry.consumeRestoredStateForKey(stateRegistryKey)
            .let(::restoreFromBundle)
        }
      }
    })

    linkModalViewTreeOwners(dialogs)

    // TODO document why this has to be at the end
    super.onAttachedToWindow()
  }

  override fun onDetachedFromWindow() {
    println("OMG MC detached")
    dumpState()
    parentStateRegistry.unregisterSavedStateProvider(stateRegistryKey)
    super.onDetachedFromWindow()
  }

  private fun saveToBundle(): Bundle = Bundle().apply {
    println("OMG MC saving to bundle")
    dumpState()

    // Don't need to save the base view's hierarchy state, the view system will automatically do
    // that.
    baseStateFrame.performSave(saveViewHierarchyState = false)
    putParcelable("base", baseStateFrame)

    dialogs.forEachIndexed { index, dialogRef ->
      // TODO this is calling saveHierarchyState on the decor view, not on the window, as the
      //  previous code did. Is that a problem?
      dialogRef.frame.performSave(saveViewHierarchyState = true)
      putParcelable(index.toString(), dialogRef.frame)
    }
  }

  /**
   * This is called as soon as the view is attached and the lifecycle is in the CREATED state.
   */
  private fun restoreFromBundle(bundle: Bundle?) {
    println("OMG MC restoring from bundle: $bundle")
    require(!isRestored) {
      "Expected restoreFromBundle to only be called once."
    }
    isRestored = true

    if (bundle == null) {
      // This always has to be called so consume doesn't throw, even if we don't actually have
      // anything to restore.
      baseStateFrame.restoreAndroidXStateRegistry()
      return
    }

    val restoredBaseFrame = bundle.getParcelable<ViewStateFrame>("base")!!
    baseStateFrame.loadAndroidXStateRegistryFrom(restoredBaseFrame)
    baseStateFrame.restoreAndroidXStateRegistry()
    // Don't need to restore the hierarchy state, the view system will automatically do that.

    dialogs.forEachIndexed { index, dialogRef ->
      val frame = bundle.getParcelable<ViewStateFrame>(index.toString())
      // Once we hit an index that doesn't exist, there will be no more entries to process so we
      // can exit early.
        ?: return

      dialogRef.frame.loadAndroidXStateRegistryFrom(frame)
      dialogRef.frame.restoreAndroidXStateRegistry()
      // TODO this is calling restoreHierarchyState on the decor view, not the window as the
      //  previous code did. Is that a problem?
      // TODO this is probably too early to make this call, need to wait until we get
      //  onRestoreHierarchyState
      dialogRef.frame.restoreViewHierarchyState()
    }

    println("OMG MC restored from bundle")
    dumpState()
  }

  /** @see [linkViewTreeOwners] */
  private fun linkModalViewTreeOwners(dialogs: List<DialogRef<*>>) {
    linkViewTreeOwners(this, dialogs.asSequence().map {
      Pair(it.dialog.decorView!!, it.frame)
    })
  }

  /**
   * @param extra optional hook to allow subclasses to associate extra data with this dialog,
   * e.g. its content view. Not considered for equality.
   */
  @WorkflowUiExperimentalApi
  protected class DialogRef<ModalRenderingT : Any>(
    public val modalRendering: ModalRenderingT,
    public val viewEnvironment: ViewEnvironment,
    public val dialog: Dialog,
    public val extra: Any? = null
  ) {
    private val key get() = Named.keyFor(modalRendering, "modal")

    internal lateinit var frame: ViewStateFrame

    internal fun initFrame() {
      frame = ViewStateFrame(key)
    }

    internal fun withUpdate(
      rendering: ModalRenderingT,
      environment: ViewEnvironment
    ) = DialogRef(rendering, environment, dialog, extra).also {
      it.frame = frame
    }

    /**
     * Call this instead of calling `dialog.dismiss()` directly – this method ensures that the modal's
     * [WorkflowLifecycleOwner] is destroyed correctly.
     */
    internal fun dismiss() {
      // The dialog's views are about to be detached, and when that happens we want to transition
      // the dialog view's lifecycle to a terminal state even though the parent is probably still
      // alive.
      println("OMG MC destroying modal on detach: $key")
      dialog.decorView?.let(WorkflowLifecycleOwner::get)?.destroyOnDetach()
      dialog.dismiss()
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as DialogRef<*>

      if (dialog != other.dialog) return false

      return true
    }

    override fun hashCode(): Int {
      return dialog.hashCode()
    }
  }
}

private class OnDestroy(private val block: () -> Unit) : LifecycleObserver {
  @OnLifecycleEvent(ON_DESTROY)
  fun onDestroy() = block()
}

private val Dialog.decorView: View?
  get() = window?.decorView
