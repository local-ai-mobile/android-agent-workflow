package dev.localai.agent.workflow.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.localai.agent.workflow.core.AgentExecutionTier
import dev.localai.agent.workflow.model.AgentSession
import dev.localai.agent.workflow.model.AgentSessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AgentSessionStore {
    val sessionsFlow: StateFlow<List<AgentSession>>
    fun saveSession(session: AgentSession)
    fun getSession(id: String): AgentSession?
    fun getSessionById(id: String): AgentSession? = getSession(id)
    fun listSessions(): List<AgentSession>
    fun getAllSessions(): List<AgentSession> = listSessions()
    fun deleteSession(id: String): Boolean
    fun clearAll()
    fun clearAllSessions() = clearAll()
    fun togglePin(id: String): Boolean
    fun renameSession(id: String, newTitle: String): Boolean
}

fun AgentSessionStore(context: Context): AgentSessionStore = DefaultAgentSessionStore(context)

class DefaultAgentSessionStore(
    context: Context,
    databaseName: String? = AgentDatabaseHelper.DATABASE_NAME
) : AgentSessionStore {

    private val dbHelper = AgentDatabaseHelper(context.applicationContext, databaseName)
    private val _sessionsFlow = MutableStateFlow<List<AgentSession>>(emptyList())
    override val sessionsFlow: StateFlow<List<AgentSession>> = _sessionsFlow.asStateFlow()

    init {
        reload()
    }

    @Synchronized
    private fun reload() {
        _sessionsFlow.value = listSessions()
    }

    @Synchronized
    override fun saveSession(session: AgentSession) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", session.id)
            put("title", session.title)
            put("objective", session.objective)
            put("modelId", session.modelId)
            put("status", session.status.name)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
            put("isPinned", if (session.isPinned) 1 else 0)
            put("isolatedScratchpadJson", StepJsonSerializer.serializeStringList(session.isolatedScratchpad))
            put("subAgentsJson", StepJsonSerializer.serializeSubAgents(session.subAgents))
            put("stepsJson", StepJsonSerializer.serializeSteps(session.steps))
            put("pendingToolCallJson", StepJsonSerializer.serializeToolCall(session.pendingToolCall))
            put("remainingPlannedCallsJson", StepJsonSerializer.serializeToolCalls(session.remainingPlannedCalls))
            put("isPipelineTask", if (session.isPipelineTask) 1 else 0)
            put("pipelineStagesJson", StepJsonSerializer.serializeStringList(session.pipelineStages))
            put("executionTier", session.executionTier.name)
            put("slashCommand", session.slashCommand)
            put("isPlanApproved", if (session.isPlanApproved) 1 else 0)
        }
        db.insertWithOnConflict(
            AgentDatabaseHelper.TABLE_AGENT_SESSIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        reload()
    }

    @Synchronized
    override fun listSessions(): List<AgentSession> {
        val list = mutableListOf<AgentSession>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            AgentDatabaseHelper.TABLE_AGENT_SESSIONS,
            null,
            null, null, null, null,
            "isPinned DESC, updatedAt DESC"
        )
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val titleIdx = c.getColumnIndexOrThrow("title")
            val objIdx = c.getColumnIndexOrThrow("objective")
            val modelIdx = c.getColumnIndexOrThrow("modelId")
            val statusIdx = c.getColumnIndexOrThrow("status")
            val createdIdx = c.getColumnIndexOrThrow("createdAt")
            val updatedIdx = c.getColumnIndexOrThrow("updatedAt")
            val pinnedIdx = c.getColumnIndexOrThrow("isPinned")
            val scratchpadIdx = c.getColumnIndexOrThrow("isolatedScratchpadJson")
            val subAgentsIdx = c.getColumnIndexOrThrow("subAgentsJson")
            val stepsIdx = c.getColumnIndexOrThrow("stepsJson")
            val pendingCallIdx = c.getColumnIndexOrThrow("pendingToolCallJson")
            val remainingCallsIdx = c.getColumnIndexOrThrow("remainingPlannedCallsJson")
            val pipelineIdx = c.getColumnIndexOrThrow("isPipelineTask")
            val stagesIdx = c.getColumnIndexOrThrow("pipelineStagesJson")
            val tierIdx = c.getColumnIndexOrThrow("executionTier")
            val slashIdx = c.getColumnIndexOrThrow("slashCommand")
            val planApprovedIdx = c.getColumnIndexOrThrow("isPlanApproved")

            while (c.moveToNext()) {
                val scratchpad = StepJsonSerializer.deserializeStringList(c.getString(scratchpadIdx))
                val pendingCall = StepJsonSerializer.deserializeToolCall(c.getString(pendingCallIdx))
                val remainingCalls = StepJsonSerializer.deserializeToolCalls(c.getString(remainingCallsIdx))
                val stages = StepJsonSerializer.deserializeStringList(c.getString(stagesIdx))
                val steps = StepJsonSerializer.deserializeSteps(c.getString(stepsIdx))
                val subAgents = StepJsonSerializer.deserializeSubAgents(c.getString(subAgentsIdx))

                val status = try {
                    AgentSessionStatus.valueOf(c.getString(statusIdx))
                } catch (_: Exception) {
                    AgentSessionStatus.IDLE
                }

                val tier = try {
                    AgentExecutionTier.valueOf(c.getString(tierIdx))
                } catch (_: Exception) {
                    AgentExecutionTier.TIER_1_CHAT
                }

                list.add(
                    AgentSession(
                        id = c.getString(idIdx),
                        title = c.getString(titleIdx),
                        objective = c.getString(objIdx),
                        modelId = c.getString(modelIdx),
                        status = status,
                        steps = steps,
                        subAgents = subAgents,
                        pendingToolCall = pendingCall,
                        remainingPlannedCalls = remainingCalls,
                        isolatedScratchpad = scratchpad,
                        isPipelineTask = c.getInt(pipelineIdx) == 1,
                        pipelineStages = stages,
                        createdAt = c.getLong(createdIdx),
                        updatedAt = c.getLong(updatedIdx),
                        isPinned = c.getInt(pinnedIdx) == 1,
                        executionTier = tier,
                        slashCommand = c.getString(slashIdx),
                        isPlanApproved = c.getInt(planApprovedIdx) == 1
                    )
                )
            }
        }
        return list
    }

    override fun getSession(id: String): AgentSession? {
        return listSessions().find { it.id == id }
    }

    @Synchronized
    override fun deleteSession(id: String): Boolean {
        val db = dbHelper.writableDatabase
        val rows = db.delete(AgentDatabaseHelper.TABLE_AGENT_SESSIONS, "id = ?", arrayOf(id))
        reload()
        return rows > 0
    }

    @Synchronized
    override fun clearAll() {
        val db = dbHelper.writableDatabase
        db.delete(AgentDatabaseHelper.TABLE_AGENT_SESSIONS, null, null)
        reload()
    }

    @Synchronized
    override fun togglePin(id: String): Boolean {
        val session = getSession(id) ?: return false
        val newPinned = !session.isPinned
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("isPinned", if (newPinned) 1 else 0)
            put("updatedAt", System.currentTimeMillis())
        }
        val rows = db.update(AgentDatabaseHelper.TABLE_AGENT_SESSIONS, values, "id = ?", arrayOf(id))
        reload()
        return rows > 0
    }

    @Synchronized
    override fun renameSession(id: String, newTitle: String): Boolean {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return false
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("title", trimmed)
            put("updatedAt", System.currentTimeMillis())
        }
        val rows = db.update(AgentDatabaseHelper.TABLE_AGENT_SESSIONS, values, "id = ?", arrayOf(id))
        reload()
        return rows > 0
    }
}
