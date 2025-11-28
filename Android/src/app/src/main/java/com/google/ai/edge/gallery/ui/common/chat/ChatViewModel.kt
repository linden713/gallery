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

package com.google.ai.edge.gallery.ui.common.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Conversation
import com.google.ai.edge.gallery.data.HistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import android.content.Context
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers

private const val TAG = "AGChatViewModel"

data class ChatUiState(
  /** Indicates whether the runtime is currently processing a message. */
  val inProgress: Boolean = false,

  /** Indicates whether the session is being reset. */
  val isResettingSession: Boolean = false,

  /**
   * Indicates whether the model is preparing (before outputting any result and after initializing).
   */
  val preparing: Boolean = false,

  /** A map of model names to lists of chat messages. */
  val messagesByModel: Map<String, MutableList<ChatMessage>> = mapOf(),

  /** A map of model names to the currently streaming chat message. */
  val streamingMessagesByModel: Map<String, ChatMessage> = mapOf(),

  /*
   * A map of model names to a map of chat messages to a boolean indicating whether the message is
   * showing the stats below it.
   */
  val showingStatsByModel: Map<String, MutableSet<ChatMessage>> = mapOf(),

  /** The list of saved conversations. */
  val history: List<Conversation> = emptyList(),
  
  /** The ID of the current conversation. */
  val currentConversationId: String? = null
)

/** ViewModel responsible for managing the chat UI state and handling chat-related operations. */
abstract class ChatViewModel() : ViewModel() {
  private val _uiState = MutableStateFlow(createUiState())
  val uiState = _uiState.asStateFlow()
  
  // This will be injected in the concrete classes
  protected open var historyRepository: HistoryRepository? = null
  protected open var applicationContext: Context? = null

  fun setDependencies(repository: HistoryRepository, context: Context) {
      this.historyRepository = repository
      this.applicationContext = context
      loadHistory()
  }

  private fun loadHistory() {
      val repo = historyRepository ?: return
      val context = applicationContext ?: return
      viewModelScope.launch {
          val history = repo.loadHistory(context)
          _uiState.update { it.copy(history = history.sortedByDescending { c -> c.timestamp }) }
      }
  }

  fun startNewChat(model: Model) {
      clearAllMessages(model)
      _uiState.update { it.copy(currentConversationId = UUID.randomUUID().toString()) }
  }

  fun loadConversation(conversation: Conversation, model: Model) {
      val repo = historyRepository ?: return
      
      // Clear current messages
      clearAllMessages(model)
      
      // Load messages from conversation
      val messages = conversation.messages.map { repo.mapToChatMessage(it) }
      
      val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
      newMessagesByModel[model.name] = messages.toMutableList()
      
      _uiState.update { 
          it.copy(
              messagesByModel = newMessagesByModel,
              currentConversationId = conversation.id
          ) 
      }
  }

  fun saveCurrentConversation(model: Model) {
      val repo = historyRepository ?: return
      val context = applicationContext ?: return
      
      val messages = _uiState.value.messagesByModel[model.name] ?: return
      if (messages.isEmpty()) return

      val firstUserMessage = messages.firstOrNull { it.side == ChatSide.USER && it is ChatMessageText } as? ChatMessageText
      val title = firstUserMessage?.content?.take(30) ?: "New Chat"
      
      val serializableMessages = messages.mapNotNull { repo.mapToSerializable(it) }
      

      
      if (serializableMessages.isEmpty()) return

      // Optimistically update currentConversationId if it's null to prevent race conditions
      // where multiple saves (e.g. from addMessage) generate different IDs.
      var currentId = _uiState.value.currentConversationId
      if (currentId == null) {
          currentId = UUID.randomUUID().toString()
          _uiState.update { it.copy(currentConversationId = currentId) }
      }
      
      val conversation = Conversation(
          id = currentId!!,
          title = title,
          messages = serializableMessages,
          timestamp = System.currentTimeMillis(),
          modelName = model.name
      )

      viewModelScope.launch {
          val currentHistory = _uiState.value.history.toMutableList()
          val existingIndex = currentHistory.indexOfFirst { it.id == currentId }
          
          if (existingIndex != -1) {
              currentHistory[existingIndex] = conversation
          } else {
              currentHistory.add(0, conversation)
          }
          
          // Sort by timestamp desc
          currentHistory.sortByDescending { it.timestamp }
          
          repo.saveHistory(context, currentHistory)
          
          _uiState.update { 
              it.copy(
                  history = currentHistory,
                  // currentConversationId is already set
              ) 
          }
      }
  }

  fun deleteConversations(ids: Set<String>) {
      val repo = historyRepository ?: return
      val context = applicationContext ?: return
      
      viewModelScope.launch {
          repo.deleteConversations(context, ids)
          
          // Reload history to update UI
          val history = repo.loadHistory(context)
          
          // If current conversation was deleted, clear it
          var currentId = _uiState.value.currentConversationId
          if (ids.contains(currentId)) {
              currentId = null
              // Optionally clear messages for the current model if needed, 
              // but for now just resetting the ID is enough to indicate "new chat" state visually if we wanted.
              // However, usually we might want to start a new chat or just leave the screen as is.
              // Let's just update the history list.
          }
          
          _uiState.update { 
              it.copy(
                  history = history.sortedByDescending { c -> c.timestamp },
                  currentConversationId = currentId
              ) 
          }
      }
  }

  fun addMessage(model: Model, message: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    newMessagesByModel[model.name] = newMessages
    // Remove prompt template message if it is the current last message.
    if (newMessages.size > 0 && newMessages.last().type == ChatMessageType.PROMPT_TEMPLATES) {
      newMessages.removeAt(newMessages.size - 1)
    }
    newMessages.add(message)
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
    
    // Auto-save if it's a user message or a completed agent response (simplified trigger)
    if (message.side == ChatSide.USER || message.side == ChatSide.AGENT) {
        saveCurrentConversation(model)
    }
  }

  fun insertMessageAfter(model: Model, anchorMessage: ChatMessage, messageToAdd: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    newMessagesByModel[model.name] = newMessages
    // Find the index of the anchor message
    val anchorIndex = newMessages.indexOf(anchorMessage)
    if (anchorIndex != -1) {
      // Insert the new message after the anchor message
      newMessages.add(anchorIndex + 1, messageToAdd)
    }
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun removeMessageAt(model: Model, index: Int) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList()
    if (newMessages != null) {
      newMessagesByModel[model.name] = newMessages
      if (index >= 0 && index < newMessages.size) {
        newMessages.removeAt(index)
      }
    }
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun removeLastMessage(model: Model) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      newMessages.removeAt(newMessages.size - 1)
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun clearAllMessages(model: Model) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    newMessagesByModel[model.name] = mutableListOf()
    _uiState.update { _uiState.value.copy(messagesByModel = newMessagesByModel) }
  }

  fun getLastMessage(model: Model): ChatMessage? {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).lastOrNull()
  }

  fun updateLastTextMessageContentIncrementally(
    model: Model,
    partialContent: String,
    latencyMs: Float,
  ) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageText) {
        val newContent = processLlmResponse(response = "${lastMessage.content}${partialContent}")
        val newLastMessage =
          ChatMessageText(
            content = newContent,
            side = lastMessage.side,
            latencyMs = latencyMs,
            accelerator = lastMessage.accelerator,
          )
        newMessages.removeAt(newMessages.size - 1)
        newMessages.add(newLastMessage)
      }
    }
    newMessagesByModel[model.name] = newMessages
    val newUiState = _uiState.value.copy(messagesByModel = newMessagesByModel)
    _uiState.update { newUiState }
  }

  fun updateLastTextMessageLlmBenchmarkResult(
    model: Model,
    llmBenchmarkResult: ChatMessageBenchmarkLlmResult,
  ) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageText) {
        lastMessage.llmBenchmarkResult = llmBenchmarkResult
        newMessages.removeAt(newMessages.size - 1)
        newMessages.add(lastMessage)
      }
    }
    newMessagesByModel[model.name] = newMessages
    val newUiState = _uiState.value.copy(messagesByModel = newMessagesByModel)
    _uiState.update { newUiState }
  }

  fun replaceLastMessage(model: Model, message: ChatMessage, type: ChatMessageType) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      val index = newMessages.indexOfLast { it.type == type }
      if (index >= 0) {
        newMessages[index] = message
      }
    }
    newMessagesByModel[model.name] = newMessages
    val newUiState = _uiState.value.copy(messagesByModel = newMessagesByModel)
    _uiState.update { newUiState }
  }

  fun replaceMessage(model: Model, index: Int, message: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (index >= 0 && index < newMessages.size) {
      newMessages[index] = message
    }
    newMessagesByModel[model.name] = newMessages
    val newUiState = _uiState.value.copy(messagesByModel = newMessagesByModel)
    _uiState.update { newUiState }
  }

  fun updateStreamingMessage(model: Model, message: ChatMessage) {
    val newStreamingMessagesByModel = _uiState.value.streamingMessagesByModel.toMutableMap()
    newStreamingMessagesByModel[model.name] = message
    _uiState.update { _uiState.value.copy(streamingMessagesByModel = newStreamingMessagesByModel) }
  }

  fun setInProgress(inProgress: Boolean) {
    _uiState.update { _uiState.value.copy(inProgress = inProgress) }
  }

  fun setIsResettingSession(isResettingSession: Boolean) {
    _uiState.update { _uiState.value.copy(isResettingSession = isResettingSession) }
  }

  fun setPreparing(preparing: Boolean) {
    _uiState.update { _uiState.value.copy(preparing = preparing) }
  }

  fun addConfigChangedMessage(
    oldConfigValues: Map<String, Any>,
    newConfigValues: Map<String, Any>,
    model: Model,
  ) {
    Log.d(TAG, "Adding config changed message. Old: ${oldConfigValues}, new: $newConfigValues")
    val message =
      ChatMessageConfigValuesChange(
        model = model,
        oldValues = oldConfigValues,
        newValues = newConfigValues,
      )
    addMessage(message = message, model = model)
  }

  fun getMessageIndex(model: Model, message: ChatMessage): Int {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).indexOf(message)
  }

  fun isShowingStats(model: Model, message: ChatMessage): Boolean {
    return _uiState.value.showingStatsByModel[model.name]?.contains(message) ?: false
  }

  fun toggleShowingStats(model: Model, message: ChatMessage) {
    val newShowingStatsByModel = _uiState.value.showingStatsByModel.toMutableMap()
    val newShowingStats = newShowingStatsByModel[model.name]?.toMutableSet() ?: mutableSetOf()
    if (newShowingStats.contains(message)) {
      newShowingStats.remove(message)
    } else {
      newShowingStats.add(message)
    }
    newShowingStatsByModel[model.name] = newShowingStats
    _uiState.update { _uiState.value.copy(showingStatsByModel = newShowingStatsByModel) }
  }

  private fun createUiState(): ChatUiState {
    return ChatUiState()
  }
}
