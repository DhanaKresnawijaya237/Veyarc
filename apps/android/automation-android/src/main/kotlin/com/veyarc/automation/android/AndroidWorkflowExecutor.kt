package com.veyarc.automation.android

import android.content.Context
import com.veyarc.engine.AutomationDriver
import com.veyarc.engine.CancellationSignal
import com.veyarc.engine.CancellationSignals
import com.veyarc.engine.ExecutionClock
import com.veyarc.engine.PauseSignal
import com.veyarc.engine.PauseSignals
import com.veyarc.engine.RunEvent
import com.veyarc.engine.WorkflowCompilationResult
import com.veyarc.engine.WorkflowCompiler
import com.veyarc.engine.WorkflowRunResult
import com.veyarc.engine.WorkflowRunner
import com.veyarc.model.WorkflowDocument

/** Compiles and executes a workflow through the current Android capability snapshot. */
class AndroidWorkflowExecutor private constructor(
    private val dependencies: Dependencies,
) {
    constructor(context: Context) : this(
        dependencies = Dependencies(
            capabilitiesProvider = {
                AndroidAutomationCapabilities.snapshot(context.applicationContext)
            },
            driverProvider = { AndroidAutomationDriver(context.applicationContext) },
            clock = AndroidExecutionClock,
            runner = WorkflowRunner(),
        ),
    )

    internal constructor(
        capabilities: () -> AndroidCapabilitySnapshot,
        driver: () -> AutomationDriver,
        clock: ExecutionClock,
        runner: WorkflowRunner,
    ) : this(
        dependencies = Dependencies(
            capabilitiesProvider = capabilities,
            driverProvider = driver,
            clock = clock,
            runner = runner,
        ),
    )

    fun execute(
        document: WorkflowDocument,
        cancellation: CancellationSignal = CancellationSignals.NONE,
        pause: PauseSignal = PauseSignals.NONE,
        eventSink: (RunEvent) -> Unit = {},
    ): AndroidWorkflowExecution {
        val capabilities = dependencies.capabilitiesProvider.invoke()
        val compilation = WorkflowCompiler.compile(document, capabilities.engineCapabilities)
        val plan = compilation.plan
            ?: return AndroidWorkflowExecution(
                capabilities = capabilities,
                compilation = compilation,
                run = null,
            )
        val run = dependencies.runner.run(
            plan = plan,
            driver = dependencies.driverProvider.invoke(),
            clock = dependencies.clock,
            cancellation = cancellation,
            pause = pause,
            eventSink = eventSink,
        )
        return AndroidWorkflowExecution(capabilities, compilation, run)
    }

    private data class Dependencies(
        val capabilitiesProvider: () -> AndroidCapabilitySnapshot,
        val driverProvider: () -> AutomationDriver,
        val clock: ExecutionClock,
        val runner: WorkflowRunner,
    )
}

data class AndroidWorkflowExecution(
    val capabilities: AndroidCapabilitySnapshot,
    val compilation: WorkflowCompilationResult,
    val run: WorkflowRunResult?,
) {
    val isSuccess: Boolean
        get() = compilation.isSuccess && run?.isSuccess == true
}
