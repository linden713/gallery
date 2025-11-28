/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.data

import android.content.Context
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable
data class SerializableChatMessage(
    val content: String,
    val side: String, // "USER", "AGENT", "SYSTEM"
    val type: String, // "TEXT", etc.
    val timestamp: Long
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<SerializableChatMessage>,
    val timestamp: Long,
    val modelName: String
)

@Singleton
class HistoryRepository @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val fileName = "chat_history.json"

    suspend fun saveHistory(context: Context, history: List<Conversation>) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, fileName)
                val jsonString = json.encodeToString(history)
                file.writeText(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun loadHistory(context: Context): List<Conversation> {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, fileName)
                if (!file.exists()) return@withContext emptyList()
                val jsonString = file.readText()
                json.decodeFromString<List<Conversation>>(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun deleteConversations(context: Context, ids: Set<String>) {
        withContext(Dispatchers.IO) {
            try {
                val currentHistory = loadHistory(context).toMutableList()
                currentHistory.removeAll { ids.contains(it.id) }
                saveHistory(context, currentHistory)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun mapToSerializable(message: ChatMessage): SerializableChatMessage? {
        if (message is ChatMessageText) {
            return SerializableChatMessage(
                content = message.content,
                side = message.side.name,
                type = message.type.name,
                timestamp = System.currentTimeMillis()
            )
        }
        // For now, only persist text messages
        return null
    }

    fun mapToChatMessage(serializable: SerializableChatMessage): ChatMessage {
        val side = try { ChatSide.valueOf(serializable.side) } catch (e: Exception) { ChatSide.SYSTEM }
        return ChatMessageText(
            content = serializable.content,
            side = side,
            isMarkdown = true
        )
    }
}
