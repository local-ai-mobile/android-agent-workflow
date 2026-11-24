package dev.localai.agent.workflow.sample

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.localai.agent.workflow.core.AgentApprovalMode
import dev.localai.agent.workflow.core.AgentWorkflowEngine
import dev.localai.agent.workflow.model.AgentSessionStatus
import dev.localai.agent.workflow.model.AgentStep
import dev.localai.agent.workflow.tool.AgentTool
import dev.localai.agent.workflow.tool.AgentToolParameter
import dev.localai.agent.workflow.tool.AgentToolResult
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SampleMainActivity : AppCompatActivity() {

    private lateinit var engine: AgentWorkflowEngine
    private var currentSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sample_main)

        val inputObjective = findViewById<EditText>(R.id.inputObjective)
        val btnRun = findViewById<Button>(R.id.btnRun)
        val btnApprove = findViewById<Button>(R.id.btnApprove)
        val btnDeny = findViewById<Button>(R.id.btnDeny)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvLog = findViewById<TextView>(R.id.tvLog)

        engine = AgentWorkflowEngine.Builder(applicationContext)
            .withApprovalMode(AgentApprovalMode.SENSITIVE_ONLY)
            .registerTool(createClockTool())
            .registerTool(createMathTool())
            .registerTool(createNotesTool())
            .build()

        lifecycleScope.launch {
            engine.sessionState.collectLatest { session ->
                if (session == null) return@collectLatest
                currentSessionId = session.id
                tvStatus.text = "Status: ${session.status} | Tier: ${session.executionTier}"

                val logBuilder = StringBuilder()
                logBuilder.appendLine("Objective: ${session.objective}")
                logBuilder.appendLine("Steps:")
                session.steps.forEach { step ->
                    when (step) {
                        is AgentStep.UserPromptStep -> logBuilder.appendLine("• User: ${step.prompt}")
                        is AgentStep.ThoughtStep -> logBuilder.appendLine("• Thought [${step.stepNumber}]: ${step.thought}")
                        is AgentStep.PlanReviewStep -> logBuilder.appendLine("• Plan Review: ${step.stages.joinToString(", ")}")
                        is AgentStep.ToolCallStep -> logBuilder.appendLine("• Tool Call: ${step.toolCall.toolName} (Status: ${step.approvalStatus})")
                        is AgentStep.ObservationStep -> logBuilder.appendLine("• Observation: ${step.result.output}")
                        is AgentStep.VerificationStep -> logBuilder.appendLine("• Verification: ${step.summary}")
                        is AgentStep.ResolutionStep -> logBuilder.appendLine("• Final Resolution:\n${step.finalResponse}")
                        else -> {}
                    }
                }
                tvLog.text = logBuilder.toString()

                val isAwaiting = session.status == AgentSessionStatus.AWAITING_APPROVAL
                btnApprove.isEnabled = isAwaiting
                btnDeny.isEnabled = isAwaiting
            }
        }

        btnRun.setOnClickListener {
            val text = inputObjective.text.toString().trim()
            if (text.isNotBlank()) {
                lifecycleScope.launch {
                    engine.executeObjective(text)
                }
            }
        }

        btnApprove.setOnClickListener {
            val id = currentSessionId ?: return@setOnClickListener
            lifecycleScope.launch {
                val current = engine.sessionState.value
                if (current?.slashCommand == "/plan" && !current.isPlanApproved) {
                    engine.approvePlan(id)
                } else {
                    engine.approvePendingStep(id)
                }
            }
        }

        btnDeny.setOnClickListener {
            val id = currentSessionId ?: return@setOnClickListener
            lifecycleScope.launch {
                engine.denyPendingStep(id, "Cancelled by user")
            }
        }
    }

    private fun createClockTool() = object : AgentTool {
        override val name = "device_clock_timer"
        override val description = "Provides live device timestamp"
        override val parameters = listOf(AgentToolParameter("action", "string", "Action"))
        override val isSensitive = false
        override val isDeterministic = true
        override fun isCallSensitive(arguments: Map<String, String>) = false
        override suspend fun execute(context: Context, arguments: Map<String, String>) =
            AgentToolResult(name, true, "UTC Time: ${System.currentTimeMillis()}")
    }

    private fun createMathTool() = object : AgentTool {
        override val name = "math_evaluator"
        override val description = "Arithmetic calculations"
        override val parameters = listOf(AgentToolParameter("expression", "string", "Math expression"))
        override val isSensitive = false
        override val isDeterministic = true
        override fun isCallSensitive(arguments: Map<String, String>) = false
        override suspend fun execute(context: Context, arguments: Map<String, String>) =
            AgentToolResult(name, true, "Calculated Result: 42")
    }

    private fun createNotesTool() = object : AgentTool {
        override val name = "notes_manager"
        override val description = "Vault storage"
        override val parameters = listOf(AgentToolParameter("action", "string", "create/read"))
        override val isSensitive = true
        override fun isCallSensitive(arguments: Map<String, String>) = arguments["action"] == "create"
        override suspend fun execute(context: Context, arguments: Map<String, String>) =
            AgentToolResult(name, true, "Saved note successfully.")
    }
}
