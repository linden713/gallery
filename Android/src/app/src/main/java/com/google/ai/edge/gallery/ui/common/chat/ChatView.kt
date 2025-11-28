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

// import com.google.ai.edge.gallery.ui.preview.PreviewChatModel
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
// import com.google.ai.edge.gallery.ui.preview.TASK_TEST1
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.ModelPageAppBar
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import com.google.ai.edge.gallery.ui.common.ConfigDialog
import com.google.ai.edge.gallery.data.convertValueToTargetType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch

private const val TAG = "AGChatView"

/**
 * A composable that displays a chat interface, allowing users to interact with different models
 * associated with a given task.
 *
 * This composable provides a horizontal pager for switching between models, a model selector for
 * configuring the selected model, and a chat panel for sending and receiving messages. It also
 * manages model initialization, cleanup, and download status, and handles navigation and system
 * back gestures.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatView(
  task: Task,
  viewModel: ChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  onSendMessage: (Model, List<ChatMessage>) -> Unit,
  onRunAgainClicked: (Model, ChatMessage) -> Unit,
  onBenchmarkClicked: (Model, ChatMessage, Int, Int) -> Unit,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onResetSessionClicked: (Model) -> Unit = {},
  onStreamImageMessage: (Model, ChatMessageImage) -> Unit = { _, _ -> },
  onStopButtonClicked: (Model) -> Unit = {},
  showStopButtonInInputWhenInProgress: Boolean = false,
  onSettingsClicked: () -> Unit = {},
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel

  // History selection state
  var isSelectionMode by remember { mutableStateOf(false) }
  var selectedHistoryIds by remember { mutableStateOf(setOf<String>()) }

  // Image viewer related.
  var selectedImageIndex by remember { mutableIntStateOf(-1) }
  var allImageViewerImages by remember { mutableStateOf<List<Bitmap>>(listOf()) }
  var showImageViewer by remember { mutableStateOf(false) }

  // Config dialog related.
  var showConfigDialog by remember { mutableStateOf(false) }
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  val isModelInitializing =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
  val isModelInitialized =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED

  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var navigatingUp by remember { mutableStateOf(false) }
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

  val handleNavigateUp = {
    navigatingUp = true
    navigateUp()

    // clean up all models.
    scope.launch(Dispatchers.Default) {
      // Ensure drawer is closed
      drawerState.close()
      for (model in task.models) {
        modelManagerViewModel.cleanupModel(context = context, task = task, model = model)
      }
    }
  }



  // Initialize model when model/download state changes.
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(TAG, "Initializing model '${selectedModel.name}' from ChatView launched effect")
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  // Handle system's edge swipe.
  // Moved inside Scaffold/Content to ensure correct lifecycle/composition order


  // Wrap in Surface to ensure solid background during transitions
  androidx.compose.material3.Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background
  ) {
      ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !navigatingUp,
        drawerContent = {
          ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.75f)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
          // Top section
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val downloadSucceeded = curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
            val showResetSessionButton = true // Always show, but maybe disabled
            val showConfigButton = selectedModel.configs.isNotEmpty() && downloadSucceeded

            if (showResetSessionButton) {
               val enableResetButton = !isModelInitializing && !uiState.preparing && isModelInitialized
               NavigationDrawerItem(
                 label = { Text(text = "New Chat") },
                 icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                 selected = false,
                 onClick = {
                   if (enableResetButton) {
                     scope.launch { drawerState.close() }
                     viewModel.startNewChat(selectedModel)
                     onResetSessionClicked(selectedModel)
                   }
                 },
                 modifier = Modifier.alpha(if (enableResetButton) 1f else 0.5f)
               )
            }

            if (showConfigButton) {
              val enableConfigButton = !isModelInitializing && !uiState.inProgress && isModelInitialized
              NavigationDrawerItem(
                label = { Text(text = "Model Config") },
                icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                selected = false,
                onClick = {
                  if (enableConfigButton) {
                    scope.launch { drawerState.close() }
                    showConfigDialog = true
                  }
                },
                modifier = Modifier.alpha(if (enableConfigButton) 1f else 0.5f)
              )
            }
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
          
          HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
          
          Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
          ) {
              Text(
                  text = if (isSelectionMode) "${selectedHistoryIds.size} Selected" else "History",
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.primary
              )
              
              if (isSelectionMode) {
                  IconButton(
                      onClick = {
                          viewModel.deleteConversations(selectedHistoryIds)
                          isSelectionMode = false
                          selectedHistoryIds = emptySet()
                      }
                  ) {
                      Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                  }
              }
          }

          LazyColumn(modifier = Modifier.weight(1f)) {
              items(uiState.history) { conversation ->
                  val isSelected = selectedHistoryIds.contains(conversation.id)
                  val isCurrent = conversation.id == uiState.currentConversationId
                  
                  HistoryItem(
                      title = conversation.title,
                      isSelected = isSelected,
                      isCurrent = isCurrent,
                      isSelectionMode = isSelectionMode,
                      onClick = {
                          if (isSelectionMode) {
                              selectedHistoryIds = if (isSelected) {
                                  selectedHistoryIds - conversation.id
                              } else {
                                  selectedHistoryIds + conversation.id
                              }
                              if (selectedHistoryIds.isEmpty()) {
                                  isSelectionMode = false
                              }
                          } else {
                              scope.launch { drawerState.close() }
                              viewModel.loadConversation(conversation, selectedModel)
                          }
                      },
                      onLongClick = {
                          if (!isSelectionMode) {
                              isSelectionMode = true
                              selectedHistoryIds = setOf(conversation.id)
                          }
                      }
                  )
              }
          }

          // Bottom section
          NavigationDrawerItem(
            label = { Text(text = "Settings") },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            selected = false,
            onClick = {
              scope.launch { drawerState.close() }
              onSettingsClicked()
            }
          )
        }
      }
    }
  ) {
  Scaffold(
    modifier = modifier,
    topBar = {
      ModelPageAppBar(
        task = task,
        model = selectedModel,
        modelManagerViewModel = modelManagerViewModel,
        canShowResetSessionButton = false, // Moved to sidebar
        isResettingSession = uiState.isResettingSession,
        inProgress = uiState.inProgress,
        modelPreparing = uiState.preparing,
        onResetSessionClicked = {}, // Handled in sidebar
        onConfigChanged = { old, new ->
          viewModel.addConfigChangedMessage(
            oldConfigValues = old,
            newConfigValues = new,
            model = selectedModel,
          )
        },
        onBackClicked = { handleNavigateUp() },
        onModelSelected = { prevModel, curModel ->
          if (prevModel.name != curModel.name) {
            modelManagerViewModel.cleanupModel(context = context, task = task, model = prevModel)
          }
          modelManagerViewModel.selectModel(model = curModel)
        },
        onMenuClicked = {
          scope.launch {
            drawerState.open()
          }
        },
      )
    },
  ) { innerPadding ->
    Box {
      // val curSelectedModel = task.models[pageIndex]
      val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
      
      BackHandler {
        val modelInitializationStatus =
          modelManagerUiState.modelInitializationStatus[selectedModel.name]
        val isModelInitializing =
          modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
        if (!isModelInitializing && !uiState.inProgress) {
          handleNavigateUp()
        }
      }

      Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AnimatedContent(
          targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
        ) { targetState ->
          when (targetState) {
            // Main UI when model is downloaded.
            true ->
              ChatPanel(
                modelManagerViewModel = modelManagerViewModel,
                task = task,
                selectedModel = selectedModel,
                viewModel = viewModel,
                innerPadding = innerPadding,
                navigateUp = navigateUp,
                onSendMessage = onSendMessage,
                onRunAgainClicked = onRunAgainClicked,
                onBenchmarkClicked = onBenchmarkClicked,
                onStreamImageMessage = onStreamImageMessage,
                onStreamEnd = { averageFps ->
                  viewModel.addMessage(
                    model = selectedModel,
                    message =
                      ChatMessageInfo(
                        content = "Live camera session ended. Average FPS: $averageFps"
                      ),
                  )
                },
                onStopButtonClicked = { onStopButtonClicked(selectedModel) },
                onImageSelected = { bitmaps, selectedBitmapIndex ->
                  selectedImageIndex = selectedBitmapIndex
                  allImageViewerImages = bitmaps
                  showImageViewer = true
                },
                modifier = Modifier.weight(1f),
                showStopButtonInInputWhenInProgress = showStopButtonInInputWhenInProgress,
              )
            // Model download
            false ->
              ModelDownloadStatusInfoPanel(
                model = selectedModel,
                task = task,
                modelManagerViewModel = modelManagerViewModel,
              )
          }
        }
      }

      // Image viewer.
      AnimatedVisibility(
        visible = showImageViewer,
        enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }) + fadeOut(),
      ) {
        val pagerState =
          rememberPagerState(
            pageCount = { allImageViewerImages.size },
            initialPage = selectedImageIndex,
          )
        val scrollEnabled = remember { mutableStateOf(true) }
        Box(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
          HorizontalPager(
            state = pagerState,
            userScrollEnabled = scrollEnabled.value,
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)),
          ) { page ->
            allImageViewerImages[page].let { image ->
              ZoomableImage(bitmap = image.asImageBitmap(), pagerState = pagerState)
            }
          }

          // Close button.
          IconButton(
            onClick = { showImageViewer = false },
            colors =
              IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
              ),
            modifier = Modifier.offset(x = (-8).dp, y = 8.dp).align(Alignment.TopEnd),
          ) {
            Icon(
              Icons.Rounded.Close,
              contentDescription = stringResource(R.string.cd_close_image_viewer_icon),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }

  // Config dialog.
  if (showConfigDialog) {
    ConfigDialog(
      title = "Model configs",
      configs = selectedModel.configs,
      initialValues = selectedModel.configValues,
      onDismissed = { showConfigDialog = false },
      onOk = { curConfigValues ->
        // Hide config dialog.
        showConfigDialog = false

        // Check if the configs are changed or not. Also check if the model needs to be
        // re-initialized.
        var same = true
        var needReinitialization = false
        for (config in selectedModel.configs) {
          val key = config.key.label
          val oldValue =
            convertValueToTargetType(
              value = selectedModel.configValues.getValue(key),
              valueType = config.valueType,
            )
          val newValue =
            convertValueToTargetType(
              value = curConfigValues.getValue(key),
              valueType = config.valueType,
            )
          if (oldValue != newValue) {
            same = false
            if (config.needReinitialization) {
              needReinitialization = true
            }
            break
          }
        }
        if (same) {
          return@ConfigDialog
        }

        // Save the config values to Model.
        val oldConfigValues = selectedModel.configValues
        selectedModel.configValues = curConfigValues
        modelManagerViewModel.updateConfigValuesUpdateTrigger()

        // Force to re-initialize the model with the new configs.
        if (needReinitialization) {
          modelManagerViewModel.initializeModel(
            context = context,
            task = task,
            model = selectedModel,
            force = true,
          )
        }

        // Notify.
        viewModel.addConfigChangedMessage(
            oldConfigValues = oldConfigValues,
            newConfigValues = selectedModel.configValues,
            model = selectedModel,
          )
      },
    )
  }
  }
  }
  }


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryItem(
    title: String,
    isSelected: Boolean,
    isCurrent: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val backgroundColor = if (isCurrent && !isSelectionMode) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val contentColor = if (isCurrent && !isSelectionMode) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(backgroundColor, shape = MaterialTheme.shapes.extraSmall) // NavigationDrawerItem defaults to extraSmall or small
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp), // Inner padding
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isSelectionMode) {
            Icon(
                if (isSelected) Icons.Rounded.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else contentColor
            )
        } else {
            Icon(
                Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = contentColor
            )
        }
        
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1
        )
    }

}

// @Preview
// @Composable
// fun ChatScreenPreview() {
//   GalleryTheme {
//     val context = LocalContext.current
//     val task = TASK_TEST1
//     ChatView(
//       task = task,
//       viewModel = PreviewChatModel(context = context),
//       modelManagerViewModel = PreviewModelManagerViewModel(context = context),
//       onSendMessage = { _, _ -> },
//       onRunAgainClicked = { _, _ -> },
//       onBenchmarkClicked = { _, _, _, _ -> },
//       navigateUp = {},
//     )
//   }
// }
