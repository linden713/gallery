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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MapsUgc
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPageAppBar(
  task: Task,
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  onBackClicked: () -> Unit,
  onModelSelected: (prev: Model, cur: Model) -> Unit,
  inProgress: Boolean,
  modelPreparing: Boolean,
  modifier: Modifier = Modifier,
  isResettingSession: Boolean = false,
  onResetSessionClicked: (Model) -> Unit = {},

  canShowResetSessionButton: Boolean = false,
  onMenuClicked: (() -> Unit)? = null,
  onConfigChanged: (oldConfigValues: Map<String, Any>, newConfigValues: Map<String, Any>) -> Unit =
    { _, _ ->
    },
) {

  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val context = LocalContext.current
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]
  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]
  val isModelInitializing =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
  val isModelInitialized =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED

  CenterAlignedTopAppBar(
    title = {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        // Task type.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            task.icon ?: ImageVector.vectorResource(task.iconVectorResourceId!!),
            tint = getTaskIconColor(task = task),
            modifier = Modifier.size(16.dp),
            contentDescription = null,
          )
          Text(
            task.label,
            style = MaterialTheme.typography.titleMedium,
            color = getTaskIconColor(task = task),
          )
        }

        // Model chips pager.
        val enableModelPickerChip = !isModelInitializing && !inProgress
        ModelPickerChip(
          enabled = enableModelPickerChip,
          task = task,
          initialModel = model,
          modelManagerViewModel = modelManagerViewModel,
          onModelSelected = onModelSelected,
        )
      }
    },
    modifier = modifier,
    // The back button.
    navigationIcon = {
      val enableBackButton = !isModelInitializing && !inProgress
      IconButton(onClick = onBackClicked, enabled = enableBackButton) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
          contentDescription = stringResource(R.string.cd_navigate_back_icon),
        )
      }
    },
    // The config button for the model (if existed).
    actions = {
      if (onMenuClicked != null) {
        IconButton(onClick = onMenuClicked) {
          Icon(
            imageVector = Icons.Rounded.Menu,
            contentDescription = stringResource(R.string.cd_menu_icon),
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    },
  )


}
