# android-agent-workflow

[![Website](https://img.shields.io/badge/Website-localai.shanaka.dev-blue.svg)](https://localai.shanaka.dev/)
[![Google Play](https://img.shields.io/badge/Google_Play-Local_AI-414141.svg?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=dev.shanaka.localai)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=26)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-purple.svg)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.3.1-02303A.svg)](https://gradle.org)
[![WorkManager](https://img.shields.io/badge/AndroidX-WorkManager-green.svg)](https://developer.android.com/topic/libraries/architecture/workmanager)

> **Autonomous On-Device AI Agent Reasoning Loop with Mobile Context Distillation and WorkManager Scheduling for Android.**

`android-agent-workflow` is a modular, 100% on-device agentic framework designed specifically for Android. It features a complete **Plan-Execute-Observe-Verify** ReAct reasoning loop, an **Intelligent 3-Tier Coordinator**, mobile-first **Context Distillation**, isolated reasoning scratchpads, and battery-safe background execution via AndroidX WorkManager.

---

## 📱 Powered by Local AI

`android-agent-workflow` orchestrates the autonomous ReAct reasoning and mobile context distillation inside the **Local AI** application:
- **Official Portal**: [localai.shanaka.dev](https://localai.shanaka.dev/)
- **Get it on Google Play**: [dev.shanaka.localai](https://play.google.com/store/apps/details?id=dev.shanaka.localai)

---

## Key Features

- **Autonomous ReAct Reasoning Loop**: Plan, execute tool calls, observe results, and verify outcomes before completing tasks.
- **3-Tier Coordinator**:
  - **Tier 1 (Instant Chat)**: Direct conversational path (0 tools, 0 verification overhead, <10ms).
  - **Tier 2 (Single Tool Fast Path)**: Instant deterministic execution (clock/math 0-LLM, <15ms) or focused generative single-tool synthesis.
  - **Tier 3 (Pipeline & Slash Commands)**: Staged DAG execution with `/plan` (DAG review gate), `/boost` (10 iterations, 2048 token deep thinking), and `/swarm` (sequential sub-agent delegation).
- **Mobile Context Distillation**: Compacts bulky tool outputs (>2000 chars) into salient summaries (<=350 chars) and maintains a rolling observation window (last 4).
- **Zero Chat Token Pollution**: Raw tool outputs, intermediate thoughts, and tool schemas remain strictly inside the `isolatedScratchpad`, keeping `mainChatTokensPolluted = 0`.
- **4-Tier Tool Approval Gates**: User-controlled execution security (`ALWAYS_ASK`, `SENSITIVE_ONLY`, `AUTO_DECIDE`, `NEVER_ASK`) with double-approval gating for `/plan`.
- **Persistent SQLite Session Store**: Save, list, resume, toggle-pin, and delete multi-turn sessions with full serialization of 8 sealed `AgentStep` types.
- **Battery-Safe Background Scheduling**: WorkManager periodic workers with `battery-not-low` constraints and runtime battery-saver guards (<15% unplugged).

---

## Architecture

```
+-------------------------------------------------------------------------+
|                        Client Application Layer                         |
|         (UI, Activity/Service, Background Execution Triggers)           |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
|                dev.localai.agent.workflow.core.AgentWorkflowEngine      |
|  • Plan-Execute-Observe-Verify Autonomous Loop                          |
|  • 3-Tier Intelligent Coordinator (Tier 1 / Tier 2 / Tier 3)            |
|  • Pluggable InferenceClient (suspend (String) -> String)               |
|  • Pluggable AgentTool & AgentToolRegistry                              |
+-------------------+--------------------+--------------------------------+
                    │                    │
                    ▼                    ▼
+-----------------------------+ +-----------------------------------------+
|    compaction & prompt      | |             state & store               |
| • ContextDistiller (~300ch) | | • Copy-on-Write AgentSession State      |
| • Rolling Observation (4)   | | • Sealed AgentStep Hierarchy (8 types)  |
| • Scratchpad Token Isolation| | • SQLite SessionStore & Checkpoints     |
| • Gemma Tool Prompt Format  | | • WorkManager Periodic Battery Worker   |
+-----------------------------+ +-----------------------------------------+
```

---

## Installation

Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("dev.localai.agent:agent-workflow:0.1.0")
}
```

---

## Quickstart

```kotlin
// 1. Initialize the Engine
val engine = AgentWorkflowEngine.Builder(context)
    .withApprovalMode(AgentApprovalMode.SENSITIVE_ONLY)
    .withInferenceClient { prompt ->
        // Connect to llama.cpp, LiteRT, or Gemini Nano
        myOnDeviceLlm.generate(prompt)
    }
    .registerTool(MyCustomTool())
    .build()

// 2. Execute an Objective
lifecycleScope.launch {
    val session = engine.executeObjective("Check system telemetry and summarize notes")
    println("Status: ${session.status}")
}
```

---

## 🔗 End-to-End Recipe: Integrating All 3 Libraries

Combine `android-inference-engine`, `android-mcp-tools`, and `android-agent-workflow` into an autonomous 100% offline agent:

```kotlin
import dev.localai.agent.workflow.core.AgentApprovalMode
import dev.localai.agent.workflow.core.AgentWorkflowEngine
import dev.localai.agent.workflow.tool.AgentTool
import dev.localai.agent.workflow.tool.AgentToolParameter
import dev.localai.agent.workflow.tool.AgentToolResult
import dev.localaimobile.mcp.server.McpServerImpl
import dev.localaimobile.mcp.tools.McpToolRegistry
import io.github.localai.inference.api.EngineType
import io.github.localai.inference.api.LocalInference
import kotlinx.coroutines.flow.first
import org.json.JSONObject

// 1. Initialize On-Device Inference Engine (llama.cpp / LiteRT / Gemini Nano)
val inferenceEngine = LocalInference.createEngine(context, EngineType.LLAMA_CPP).apply {
    loadModel("/sdcard/Download/Qwen2.5-3B-Instruct-Q4_K_M.gguf", "Qwen/Qwen2.5-3B-Instruct")
}

// 2. Initialize MCP Standard Tool Registry (9 bundled on-device tools)
val mcpRegistry = McpToolRegistry().apply { registerStandardTools(context) }
val mcpServer = McpServerImpl(mcpRegistry)

// 3. Adapt MCP Tools to Agent Tools
val adaptedTools = mcpRegistry.listTools().map { mcpDef ->
    object : AgentTool {
        override val name = mcpDef.name
        override val description = mcpDef.description
        override val parameters = listOf(AgentToolParameter("args", "string", "JSON arguments", false))
        override val isSensitive = mcpDef.defaultSensitive
        override val isDeterministic = mcpDef.name in listOf("math_evaluator", "device_clock_timer")
        override suspend fun execute(context: Context, arguments: Map<String, String>): AgentToolResult {
            val jsonRpc = """{"jsonrpc":"2.0","method":"tools/call","params":{"name":"$name","arguments":${JSONObject(arguments)}}, "id":1}"""
            val response = mcpServer.handleJsonRpc(jsonRpc)
            return AgentToolResult(name = name, success = true, output = response)
        }
    }
}

// 4. Build AgentWorkflowEngine Connecting Inference & MCP Tools
val agentEngine = AgentWorkflowEngine.Builder(context)
    .withApprovalMode(AgentApprovalMode.SENSITIVE_ONLY)
    .withInferenceClient { prompt ->
        inferenceEngine.generateResponses(prompt).first()
    }
    .apply { adaptedTools.forEach { registerTool(it) } }
    .build()

// 5. Execute Autonomous Multi-Step Objective 100% Offline
lifecycleScope.launch {
    val session = agentEngine.executeObjective("/plan Check device telemetry, calculate available memory ratio, and summarize system health")
    println("Resolution: ${session.resolution?.finalResponse}")
}
```

---

## Testing

Run the automated Robolectric test suite:

```bash
./gradlew test
```

---

## Developer SEO Keywords
Android AI agent, autonomous reasoning loop, ReAct framework Android, mobile context compaction, WorkManager AI background worker, on-device agentic workflow, Gemma function calling, offline LLM tool calling, Android SQLite agent session.

---

## License

Copyright 2026 local-ai-mobile Authors. Licensed under the [Apache License, Version 2.0](LICENSE).
