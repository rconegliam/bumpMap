package com.rconegliam.bumpmap.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rconegliam.bumpmap.BuildConfig
import com.rconegliam.bumpmap.R
import com.rconegliam.bumpmap.sync.ARCHIVE_FULL
import com.rconegliam.bumpmap.sync.RecordingSync

/** Language tags offered in the app. An empty tag means "follow the system language". */
private val LANGUAGE_TAGS = listOf("", "en", "pt-BR")

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val selectedTag = remember { AppCompatDelegate.getApplicationLocales().toLanguageTags() }
    val labels = mapOf(
        "" to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_english),
        "pt-BR" to stringResource(R.string.language_portuguese),
    )

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            LANGUAGE_TAGS.forEach { tag ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = tag == selectedTag,
                            onClick = { setAppLanguage(tag) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = tag == selectedTag, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(labels.getValue(tag))
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        RecordingSyncSettings()

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
        Text(
            stringResource(R.string.settings_map_data),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun RecordingSyncSettings() {
    val context = LocalContext.current.applicationContext
    var enabled by remember { mutableStateOf(RecordingSync.token(context) != null) }
    var token by remember { mutableStateOf("") }
    val workFlow = remember(context) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(RecordingSync.WORK_NAME)
    }
    val work by workFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val status = when {
        !enabled -> R.string.sync_off
        work.any { it.state == WorkInfo.State.RUNNING } -> R.string.sync_running
        work.any { !it.state.isFinished } -> R.string.sync_pending
        work.any { it.outputData.getBoolean(ARCHIVE_FULL, false) } -> R.string.sync_archive_full
        work.any { it.state == WorkInfo.State.FAILED } -> R.string.sync_failed
        else -> R.string.sync_up_to_date
    }

    Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.sync_description),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(stringResource(status), modifier = Modifier.padding(vertical = 8.dp))
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(stringResource(R.string.sync_token)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        Button(
            enabled = token.trim().length >= 32 && token.trim().none { it.isWhitespace() },
            onClick = {
                RecordingSync.enable(context, token.trim())
                token = ""
                enabled = true
            },
        ) {
            Text(stringResource(if (enabled) R.string.sync_update_token else R.string.sync_enable))
        }
        if (enabled) {
            TextButton(onClick = {
                RecordingSync.disable(context)
                token = ""
                enabled = false
            }) { Text(stringResource(R.string.sync_disable)) }
        }
    }
}

private fun setAppLanguage(tag: String) {
    val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
    AppCompatDelegate.setApplicationLocales(locales)
}
