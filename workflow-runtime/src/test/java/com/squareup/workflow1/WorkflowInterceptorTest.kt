@file:Suppress("OverridingDeprecatedMember")

package com.squareup.workflow1

import com.squareup.workflow1.WorkflowInterceptor.RenderContextInterceptor
import com.squareup.workflow1.WorkflowInterceptor.WorkflowSession
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
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

  @Test fun `intercept() intercepts calls to actionSink send`() {
    val recorder = RecordingWorkflowInterceptor()
    val intercepted = recorder.intercept(TestActionWorkflow, TestActionWorkflow.session)
    val actions = mutableListOf<WorkflowAction<String, String, String>>()

    val fakeContext = object : BaseRenderContext<String, String, String> {
      override val actionSink: Sink<WorkflowAction<String, String, String>> =
        Sink { value -> actions += value }

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

    val rendering =
      intercepted.render("props", "string", RenderContext(fakeContext, TestActionWorkflow))

    assertTrue(actions.isEmpty())
    rendering.onEvent()
    assertTrue(actions.size == 1)

    assertEquals(
      listOf("BEGIN|onRender", "END|onRender", "BEGIN|onActionSent", "END|onActionSent"),
      recorder.consumeEventNames()
    )
  }

  @Test fun `intercept() intercepts side effects`() {
    val recorder = RecordingWorkflowInterceptor()
    val workflow = TestSideEffectWorkflow()
    val intercepted = recorder.intercept(workflow, workflow.session)
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
      ) {
        runBlocking { sideEffect() }
      }
    }

    intercepted.render("props", "string", RenderContext(fakeContext, workflow))

    assertEquals(
      listOf(
        "BEGIN|onRender",
        "BEGIN|onSideEffectRunning",
        "END|onSideEffectRunning",
        "END|onRender"
      ),
      recorder.consumeEventNames()
    )
  }

  @Test fun `intercept() uses interceptor's context for side effect`() {
    val recorder = object : RecordingWorkflowInterceptor() {
      override fun <P, S, O, R> onRender(
        renderProps: P,
        renderState: S,
        proceed: (P, S, RenderContextInterceptor<P, S, O>?) -> R,
        session: WorkflowSession
      ): R {
        return proceed(renderProps, renderState, object : RenderContextInterceptor<P, S, O> {
          override suspend fun onSideEffectRunning(
            key: String,
            proceed: suspend () -> Unit
          ) {
            val context = coroutineContext + coroutineContext.job + CoroutineName("intercepted!")
            CoroutineScope(context).run {
              proceed()
            }
          }
        })
      }
    }
    val workflow = TestSideEffectWorkflow(sideEffectNameCheck = "intercepted!")
    val intercepted = recorder.intercept(workflow, workflow.session)
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
      ) {
        runBlocking {
          sideEffect()
        }
      }
    }

    intercepted.render("props", "string", RenderContext(fakeContext, workflow))
  }

  @Test fun `intercept() throws when side effect job is changed`() {
    val recorder = object : RecordingWorkflowInterceptor() {
      override fun <P, S, O, R> onRender(
        renderProps: P,
        renderState: S,
        proceed: (P, S, RenderContextInterceptor<P, S, O>?) -> R,
        session: WorkflowSession
      ): R {
        return proceed(renderProps, renderState, object : RenderContextInterceptor<P, S, O> {
          override suspend fun onSideEffectRunning(
            key: String,
            proceed: suspend () -> Unit
          ) {
            // Creates a new Job, so sneaky.
            coroutineScope {
              proceed()
            }
          }
        })
      }
    }
    val workflow = TestSideEffectWorkflow()
    val intercepted = recorder.intercept(workflow, workflow.session)
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
      ) {
        runBlocking { sideEffect() }
      }
    }

    val error = assertFailsWith<IllegalStateException> {
      intercepted.render("props", "string", RenderContext(fakeContext, workflow))
    }
    assertEquals(
      "Expected onSideEffectStarting not to call proceed with a different Job.",
      error.message
    )
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

  private class TestRendering(val onEvent: () -> Unit)
  private object TestActionWorkflow : StatefulWorkflow<String, String, String, TestRendering>() {
    override fun initialState(
      props: String,
      snapshot: Snapshot?
    ) = ""

    override fun render(
      renderProps: String,
      renderState: String,
      context: RenderContext
    ): TestRendering {
      return TestRendering(context.eventHandler { state = "$state: fired" })
    }

    override fun snapshotState(state: String): Snapshot? = null
  }

  private class TestSideEffectWorkflow(
    val sideEffectNameCheck: String? = null
  ) : StatefulWorkflow<String, String, String, String>() {
    override fun initialState(
      props: String,
      snapshot: Snapshot?
    ) = ""

    override fun render(
      renderProps: String,
      renderState: String,
      context: RenderContext
    ): String {
      context.runningSideEffect("sideEffectKey") {
        if (sideEffectNameCheck != null) {
          suspendCoroutine<String> {
            println("NAMELY ${it.context[CoroutineName]}")
          }
          // assertEquals(sideEffectNameCheck, coroutineContext[CoroutineName]!!.name)
        }
      }
      return ""
    }

    override fun snapshotState(state: String): Snapshot? = null
  }
}
