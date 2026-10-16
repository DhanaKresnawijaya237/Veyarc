package com.veyarc.automation.android

import android.content.Context
import com.veyarc.engine.AutomationDriver
import com.veyarc.engine.CancellationSignal
import com.veyarc.engine.CancellationSignals
import com.veyarc.engine.ConditionObserver
import com.veyarc.engine.ExecutionClock
import com.veyarc.engine.PauseSignal
import com.veyarc.engine.PauseSignals
import com.veyarc.engine.RunEvent
import com.veyarc.engine.WorkflowCompilationResult
import com.veyarc.engine.WorkflowCompiler
import com.veyarc.engine.WorkflowRunResult
import com.veyarc.engine.WorkflowRunner
import com.veyarc.model.PackageName
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.usesVisualConditions

/** Compiles and executes a workflow through the current Android capability snapshot. */
class AndroidWorkflowExecutor private constructor(
    private val dependencies: Dependencies,
) {
    constructor(context: Context) : this(
        dependencies = Dependencies(
            capabilitiesProvider = {
                AndroidAutomationCapabilities.snapshot(context.applicationContext)
            },
            driverProvider = { visualConditionObserver ->
                AndroidAutomationDriver(context.applicationContext, visualConditionObserver)
            },
            clock = AndroidExecutionClock,
            runner = WorkflowRunner(),
            startupPackage = PackageName(context.applicationContext.packageName),
        ),
    )

    internal constructor(
        capabilities: () -> AndroidCapabilitySnapshot,
        driver: () -> AutomationDriver,
        clock: ExecutionClock,
        runner: WorkflowRunner,
        startupPackage: PackageName? = null,
    ) : this(
        dependencies = Dependencies(
            capabilitiesProvider = capabilities,
            driverProvider = { _ -> driver() },
            clock = clock,
            runner = runner,
            startupPackage = startupPackage,
        ),
    )

    internal constructor(
        capabilities: () -> AndroidCapabilitySnapshot,
        driverProvider: (ConditionObserver?) -> AutomationDriver,
        clock: ExecutionClock,
        runner: WorkflowRunner,
        startupPackage: PackageName? = null,
    ) : this(
        dependencies = Dependencies(
            capabilitiesProvider = capabilities,
            driverProvider = driverProvider,
            clock = clock,
            runner = runner,
            startupPackage = startupPackage,
        ),
    )

    fun execute(
        document: WorkflowDocument,
        preparedCompilation: WorkflowCompilationResult? = null,
        cancellation: CancellationSignal = CancellationSignals.NONE,
        pause: PauseSignal = PauseSignals.NONE,
        eventSink: (RunEvent) -> Unit = {},
        templateSource: ImageTemplateSource? = null,
    ): AndroidWorkflowExecution {
        val capabilities = dependencies.capabilitiesProvider.invoke()
        val compilation = preparedCompilation ?: WorkflowCompiler.compile(document, capabilities.engineCapabilities)
        val plan = compilation.plan
            ?: return AndroidWorkflowExecution(
                capabilities = capabilities,
                compilation = compilation,
                run = null,
            )
        val visualConditionObserver = if (document.usesVisualConditions()) {
            VisualConditionEvaluator(
                frameSource = MediaProjectionCaptureRuntime::captureLatestFrame,
                templateSource = templateSource ?: ImageTemplateSource { null },
                cancellation = cancellation,
            )
        } else {
            null
        }
        val run = dependencies.runner.run(
            plan = plan,
            driver = dependencies.driverProvider.invoke(visualConditionObserver),
            clock = dependencies.clock,
            cancellation = cancellation,
            pause = pause,
            eventSink = eventSink,
            startupPackage = dependencies.startupPackage,
        )
        return AndroidWorkflowExecution(capabilities, compilation, run)
    }

    private data class Dependencies(
        val capabilitiesProvider: () -> AndroidCapabilitySnapshot,
        val driverProvider: (ConditionObserver?) -> AutomationDriver,
        val clock: ExecutionClock,
        val runner: WorkflowRunner,
        val startupPackage: PackageName?,
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
