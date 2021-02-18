package com.squareup.workflow1

import com.squareup.workflow1.WorkflowInterceptor.RenderContextInterceptor
import com.squareup.workflow1.WorkflowInterceptor.WorkflowSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext

/**
 * Provides hooks into the workflow runtime that can be used to instrument or modify the behavior
 * of workflows.
 *
 * This interface's methods mirror the methods of [StatefulWorkflow]. It also has one additional
 * method, [onSessionStarted], that is notified when a workflow is started. Each method returns the
 * same thing as the corresponding method on [StatefulWorkflow], and receives the same parameters
 * as well as two extra parameters:
 *
 *  - **`proceed`** – A function that _exactly_ mirrors the corresponding function on
 *    [StatefulWorkflow], accepting the same parameters and returning the same thing. An interceptor
 *    can call this function to run the actual workflow, but it may also decide to not call it at
 *    all, or call it multiple times.
 *  - **`session`** – A [WorkflowSession] object that can be queried for information about the
 *    workflow being intercepted.
 *
 * All methods have default no-op implementations.
 *
 * ## Workflow sessions
 *
 * A single workflow may be rendered by different parents at the same time, or the same parent at
 * different, disjoint times. Each continuous sequence of renderings of a particular workflow type,
 * with the same key passed to [BaseRenderContext.renderChild], is called an "session" of that
 * workflow. The workflow's [StatefulWorkflow.initialState] method will be called at the start of
 * the session, and its state will be maintained by the runtime until the session is finished.
 * Each session is identified by the [WorkflowSession] object passed into the corresponding method
 * in a [WorkflowInterceptor].
 *
 * In addition to the [WorkflowIdentifier] of the type of the workflow being rendered, this object
 * also knows the [key][WorkflowSession.renderKey] used to render the workflow and the
 * [WorkflowSession] of the [parent][WorkflowSession.parent] workflow that is rendering it.
 *
 * Each session is also assigned a numerical ID that uniquely identifies the session over the
 * life of the entire runtime. This value will remain constant as long as the workflow's parent is
 * rendering it, and then it will never be used again. If this workflow stops being rendered, and
 * then starts again, the value will be different.
 */
@ExperimentalWorkflowApi
public interface WorkflowInterceptor {

  /**
   * Called when the session is starting, before [onInitialState].
   *
   * @param workflowScope The [CoroutineScope] that will be used for any side effects the workflow
   * runs, as well as the parent for any workflows it renders.
   */
  public fun onSessionStarted(
    workflowScope: CoroutineScope,
    session: WorkflowSession
  ): Unit = Unit

  /**
   * Intercepts calls to [StatefulWorkflow.initialState].
   */
  public fun <P, S> onInitialState(
    props: P,
    snapshot: Snapshot?,
    proceed: (P, Snapshot?) -> S,
    session: WorkflowSession
  ): S = proceed(props, snapshot)

  /**
   * Intercepts calls to [StatefulWorkflow.onPropsChanged].
   */
  public fun <P, S> onPropsChanged(
    old: P,
    new: P,
    state: S,
    proceed: (P, P, S) -> S,
    session: WorkflowSession
  ): S = proceed(old, new, state)

  /**
   * Intercepts calls to [StatefulWorkflow.render].
   */
  public fun <P, S, O, R> onRender(
    renderProps: P,
    renderState: S,
    proceed: (P, S, RenderContextInterceptor<P, S, O>?) -> R,
    session: WorkflowSession
  ): R = proceed(renderProps, renderState, null)

  /**
   * Intercepts calls to [StatefulWorkflow.snapshotState].
   */
  public fun <S> onSnapshotState(
    state: S,
    proceed: (S) -> Snapshot?,
    session: WorkflowSession
  ): Snapshot? = proceed(state)

  /**
   * Information about the session of a workflow in the runtime that a [WorkflowInterceptor] method
   * is intercepting.
   */
  @ExperimentalWorkflowApi
  public interface WorkflowSession {
    /** The [WorkflowIdentifier] that represents the type of this workflow. */
    public val identifier: WorkflowIdentifier

    /**
     * The string key argument that was passed to [BaseRenderContext.renderChild] to render this
     * workflow.
     */
    public val renderKey: String

    /**
     * A unique value that identifies the currently-running session of this workflow in the
     * runtime. See the documentation on [WorkflowInterceptor] for more information about what this
     * value represents.
     */
    public val sessionId: Long

    /** The parent [WorkflowSession] of this workflow, or null if this is the root workflow. */
    public val parent: WorkflowSession?
  }

  /**
   * Provides hooks for intercepting calls to a [BaseRenderContext], to be used from [onRender].
   *
   * For use by [onRender] methods that want to hook into action and
   * side effect events. See documentation on methods for more information about the individual
   * hooks:
   *  - [RenderContextInterceptor.onActionSent]
   *  - [RenderContextInterceptor.onSideEffectRunning]
   *
   * E.g.:
   * ```
   * override fun <P, S, O, R> onRender(
   *   renderProps: P,
   *   renderState: S,
   *   proceed: (P, S, RenderContextInterceptor<P, S, O>) -> R,
   *   session: WorkflowSession
   * ): R = proceed(renderProps, renderState, object : RenderContextInterceptor<P, S, O> {
   *   override fun onActionSent(
   *     action: WorkflowAction<P, S, O>,
   *     proceed: (WorkflowAction<P, S, O>) -> Unit
   *   ) {
   *     log("Action sent: $action")
   *     proceed(action)
   *   }
   *
   *   override suspend fun onSideEffectStarting(
   *     key: String,
   *     proceed: suspend () -> Unit
   *   ) {
   *     log("Side effect started: $key")
   *     try {
   *       proceed()
   *     } finally {
   *       log("Side effect ended: $key")
   *     }
   *   }
   * })
   * ```
   */
  public interface RenderContextInterceptor<P, S, O> {

    /**
     * Intercepts calls to [send][Sink.send] on the [BaseRenderContext.actionSink].
     *
     * This method will be called from inside the actual [Sink.send] stack frame, so any stack
     * traces captured from it will include the code that is actually making the send call.
     */
    public fun onActionSent(
      action: WorkflowAction<P, S, O>,
      proceed: (WorkflowAction<P, S, O>) -> Unit
    ) {
      proceed(action)
    }

    /**
     * Intercepts side effects that are ran via [BaseRenderContext.runningSideEffect].
     *
     * This method will only be called when a particular side effect is _started_ for a particular
     * key, and will not be called on subsequent render passes that run the side effect with the
     * same key.
     *
     * The [proceed] function will perform the actual suspending side effect, and only return when
     * the side effect is complete – this may be far in the future. This means the interceptor can
     * be notified when the side effect _ends_ by simply running code after [proceed] returns or
     * throws.
     *
     * The interceptor may run [proceed] in a different [CoroutineContext], but the context _must_
     * have the same [Job] or an exception will be thrown. This is to ensure that structured
     * concurrency is not broken. The context can change things like dispatcher, name, etc.
     */
    public suspend fun onSideEffectRunning(
      key: String,
      proceed: suspend () -> Unit,
    ) {
      proceed()
    }

    public fun <CP, CO, CR> onRenderChild(
      child: Workflow<CP, CO, CR>,
      props: CP,
      key: String,
      handler: (CO) -> WorkflowAction<P, S, O>,
      proceed: (
        child: Workflow<CP, CO, CR>,
        props: CP,
        key: String,
        handler: (CO) -> WorkflowAction<P, S, O>
      ) -> CR
    ): CR = proceed(child, props, key, handler)
  }
}

/** A [WorkflowInterceptor] that does not intercept anything. */
@ExperimentalWorkflowApi
public object NoopWorkflowInterceptor : WorkflowInterceptor

/**
 * Returns a [StatefulWorkflow] that will intercept all calls to [workflow] via this
 * [WorkflowInterceptor].
 */
@OptIn(ExperimentalWorkflowApi::class)
internal fun <P, S, O, R> WorkflowInterceptor.intercept(
  workflow: StatefulWorkflow<P, S, O, R>,
  workflowSession: WorkflowSession
): StatefulWorkflow<P, S, O, R> = if (this === NoopWorkflowInterceptor) {
  workflow
} else {
  object : StatefulWorkflow<P, S, O, R>() {
    override fun initialState(
      props: P,
      snapshot: Snapshot?
    ): S = onInitialState(props, snapshot, workflow::initialState, workflowSession)

    override fun onPropsChanged(
      old: P,
      new: P,
      state: S
    ): S = onPropsChanged(old, new, state, workflow::onPropsChanged, workflowSession)

    override fun render(
      renderProps: P,
      renderState: S,
      context: RenderContext
    ): R = onRender<P, S, O, R>(
      renderProps, renderState,
      proceed = { props, state, interceptor ->
        val interceptedContext = interceptor?.let { InterceptedRenderContext(context, it) }
          ?: context
        workflow.render(props, state, RenderContext(interceptedContext, this))
      },
      session = workflowSession
    )

    override fun snapshotState(state: S) =
      onSnapshotState(state, workflow::snapshotState, workflowSession)

    override fun toString(): String = "InterceptedWorkflow($workflow, $this@intercept)"
  }
}

@OptIn(ExperimentalWorkflowApi::class)
private class InterceptedRenderContext<P, S, O>(
  private val baseRenderContext: BaseRenderContext<P, S, O>,
  private val interceptor: RenderContextInterceptor<P, S, O>
) : BaseRenderContext<P, S, O>, Sink<WorkflowAction<P, S, O>> {
  override val actionSink: Sink<WorkflowAction<P, S, O>> get() = this

  override fun send(value: WorkflowAction<P, S, O>) {
    interceptor.onActionSent(value) { interceptedAction ->
      baseRenderContext.actionSink.send(interceptedAction)
    }
  }

  override fun <ChildPropsT, ChildOutputT, ChildRenderingT> renderChild(
    child: Workflow<ChildPropsT, ChildOutputT, ChildRenderingT>,
    props: ChildPropsT,
    key: String,
    handler: (ChildOutputT) -> WorkflowAction<P, S, O>
  ): ChildRenderingT =
    interceptor.onRenderChild(child, props, key, handler) { c, p, k, h ->
      // TODO better lambda arg names
      baseRenderContext.renderChild(c, p, k, h)
    }

  override fun runningSideEffect(
    key: String,
    sideEffect: suspend CoroutineScope.() -> Unit
  ) {
    baseRenderContext.runningSideEffect(key) {
      val outerScope = this

      // Only invoke the hook when the side effect is actually starting – i.e. don't invoke
      // it on subsequent consecutive calls with the same key.
      interceptor.onSideEffectRunning(key) {
        // We don't want to use the CoroutineScope receiver from the outer runningSideEffect
        // here since the interceptor could be calling this function from a different context
        // (e.g. wrap it with a different dispatcher or name).
        coroutineScope {
          val innerScope = this
          // If the interceptor is wrapping the coroutine context, it can't change the job
          // because that could break structured concurrency.
          check(outerScope.coroutineContext.job === innerScope.coroutineContext.job) {
            // TODO unit tests
            "Expected onSideEffectStarting not to call proceed with a different Job."
          }
          sideEffect()
        }
      }
    }
  }
}
