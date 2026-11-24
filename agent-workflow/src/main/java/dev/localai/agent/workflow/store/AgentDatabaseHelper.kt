package dev.localai.agent.workflow.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AgentDatabaseHelper(
    context: Context,
    databaseName: String? = DATABASE_NAME
) : SQLiteOpenHelper(
    context,
    databaseName,
    null,
    DATABASE_VERSION
) {
    companion object {
        const val DATABASE_NAME = "localai_agent_workflow.db"
        const val DATABASE_VERSION = 1
        const val TABLE_AGENT_SESSIONS = "agent_sessions"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_AGENT_SESSIONS (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                objective TEXT NOT NULL,
                modelId TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                isPinned INTEGER NOT NULL DEFAULT 0,
                isolatedScratchpadJson TEXT NOT NULL DEFAULT '[]',
                subAgentsJson TEXT NOT NULL DEFAULT '[]',
                stepsJson TEXT NOT NULL DEFAULT '[]',
                pendingToolCallJson TEXT,
                remainingPlannedCallsJson TEXT,
                isPipelineTask INTEGER NOT NULL DEFAULT 0,
                pipelineStagesJson TEXT NOT NULL DEFAULT '[]',
                executionTier TEXT NOT NULL DEFAULT 'TIER_1_CHAT',
                slashCommand TEXT,
                isPlanApproved INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_sessions_order ON $TABLE_AGENT_SESSIONS(isPinned DESC, updatedAt DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is baseline; future migrations go here
    }
}
