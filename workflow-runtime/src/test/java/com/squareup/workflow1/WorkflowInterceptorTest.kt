@file:Suppress("OverridingDeprecatedMember")

package com.squareup.workflow1

import com.squareup.workflow1.WorkflowInterceptor.Companion.interceptRenderContext
import com.squareup.workflow1.WorkflowInterceptor.RenderContextInterceptor
import com.squareup.workflow1.WorkflowInterceptor.WorkflowSession
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.fail

@OptIn(ExperimentalWorkflowApi::class)
internal class WorkflowInterceptorTest {

  @Test fun `intercept() returns workflow when Noop`() {
    val interceptor = NoopWorkflowInterceptor
    val workflow = Workflow.rendering("hello")
      .asStatefulWorkflow()
    val intercepted = interceptor.intercept(workflow, workflow.session)
    assertSame(workflow, intercepted)
  }

  @Test fun `intercept() intercepts calls to initialState()`() {
    val recorder = RecordingWorkflowInterceptor()
    val intercepted = recorder.intercept(TestWorkflow, TestWorkflow.session)

    val state = intercepted.initialState("props", Snapshot.of("snapshot"))

    assertEquals("props|snapshot", state)
    assertEquals(listOf("BEGIN|onInitialState", "END|onInitialState"), recorder.consumeEventNames())
  }

  @Test fun `intercept() intercepts calls to onPropsChanged()`() {
    val recorder = RecordingWorkflowInterceptor()
    val intercepted = recorder.intercept(TestWorkflow, TestWorkflow.session)

    val state = intercepted.onPropsChanged("old", "new", "state")

    assertEquals("old|new|state", state)
    assertEquals(listOf("BEGIN|onPropsChanged", "END|onPropsChanged"), recorder.consumeEventNames())
  }

  @Test fun `intercept() intercepts calls to render()`() {
    val recorder = RecordingWorkflowInterceptor()
    val intercepted = recorder.intercept(TestWorkflow, TestWorkflow.session)
    val fakeContext = object : BaseRenderContext<String, String, String> {
      override val actionSink: Sink<WorkflowAction<String, String, String>> get() = fail()

      override fun <ChildPropsT, ChildOutputT, ChildRenderingT> renderChild(
        child: Workflow<ChildPropsT, ChildOutputT, ChildRenderingT>,
        props: ChildPropsT,
        key: String,
        handler: (ChildOutputT) -> WorkflowAction<String, String, String>
      ): ChildRenderingT = fail()

      override fun runningSideEffect(
        key: String,
        sideEffect: suspend CoroutineScope.() -> Unit
      ) = fail()
    }

    val rendering = intercepted.render("props", "state", RenderContext(fakeContext, TestWorkflow))

    assertEquals("props|state", rendering)
    assertEquals(listOf("BEGIN|onRender", "END|onRender"), recorder.consumeEventNames())
  }

  @Test fun `intercept() intercepts calls to snapshotState()`() {
    val recorder = RecordingWorkflowInterceptor()
    val intercepted = recorder.intercept(TestWorkflow, TestWorkflow.session)

    val snapshot = intercepted.snapshotState("state")

    assertEquals(Snapshot.of("state"), snapshot)
    assertEquals(
      listOf("BEGIN|onSnapshotState", "END|onSnapshotState"), recorder.consumeEventNames()
    )
  }

  @Test fun `interceptRenderContext intercepts calls to actionSink send`() {
    TODO()
  }

  @Test fun `interceptRenderContext intercepts side effects`() {
    TODO()
  }

  @Test fun `interceptRenderContext uses interceptor's context for side effect`() {
    TODO()
  }

  @Test fun `interceptRenderContext throws when side effect job is changed`() {
    TODO()
  }

  private val Workflow<*, *, *>.session: WorkflowSession
    get() = object : WorkflowSession {
      override val identifier: WorkflowIdentifier = this@session.identifier
      override val renderKey: String = ""
      override val sessionId: Long = 0
      override val parent: WorkflowSession? = null
    }

  private object TestWorkflow : StatefulWorkflow<String, String, String, String>() {
    override fun initialState(
      props: String,
      snapshot: Snapshot?
    ): String = "$props|${snapshot?.bytes?.parse { it.readUtf8() }}"

    override fun onPropsChanged(
      old: String,
      new: String,
      state: String
    ): String = "$old|$new|$state"

    override fun render(
      renderProps: String,
      renderState: String,
      context: RenderContext
    ): String = "$renderProps|$renderState"

    override fun snapshotState(state: String): Snapshot = Snapshot.of(state)
  }

  private abstract class ContextInterceptingInterceptor : WorkflowInterceptor {
    protected abstract fun <P, S, O> interceptContext(): RenderContextInterceptor<P, S, O>

    override fun <P, S, O, R> onRender(
      renderProps: P,
      renderState: S,
      context: BaseRenderContext<P, S, O>,
      proceed: (P, S, BaseRenderContext<P, S, O>) -> R,
      session: WorkflowSession
    ): R {
      val interceptedContext = interceptRenderContext(context, interceptContext())
      return proceed(renderProps, renderState, interceptedContext)
    }
  }
}
