/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.sample.mainactivity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.squareup.sample.container.overviewdetail.OverviewDetailContainer
import com.squareup.sample.todo.TodoListsAppWorkflow
import com.squareup.workflow1.SimpleLoggingWorkflowInterceptor
import com.squareup.workflow1.diagnostic.tracing.TracingWorkflowInterceptor
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import com.squareup.workflow1.ui.ViewRegistry
import com.squareup.workflow1.ui.WorkflowRunner
import com.squareup.workflow1.ui.backstack.BackStackContainer
import com.squareup.workflow1.ui.setContentWorkflow

@OptIn(WorkflowUiExperimentalApi::class)
class MainActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val traceFile = getExternalFilesDir(null)?.resolve("workflow-trace-todo.json")!!

    setContentWorkflow(viewRegistry) {
      WorkflowRunner.Config(
          TodoListsAppWorkflow,
          interceptors = listOf(
              SimpleLoggingWorkflowInterceptor(),
              TracingWorkflowInterceptor(traceFile)
          )
      )
    }
  }

  private companion object {
    val viewRegistry =
      ViewRegistry(
          TodoEditorLayoutRunner,
          TodoListsViewFactory,
          OverviewDetailContainer,
          BackStackContainer
      )
  }
}