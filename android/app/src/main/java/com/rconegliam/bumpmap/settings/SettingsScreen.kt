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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.work.ExistingWorkPolicy
import com.rconegliam.bumpmap.BuildConfig
import com.rconegliam.bumpmap.R
import com.rconegliam.bumpmap.sync.RecordingSync
import com.rconegliam.bumpmap.sync.rememberSyncState

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
    val sync = rememberSyncState()
    var mobileData by remember { mutableStateOf(RecordingSync.usesMobileData(context)) }

    Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.sync_description),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    sync.message?.let { Text(stringResource(it), modifier = Modifier.padding(top = 8.dp)) }
    if (sync.pending.trips > 0 && !sync.running) {
        FilledTonalButton(
            onClick = { RecordingSync.enqueue(context, ExistingWorkPolicy.REPLACE) },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.uploads_retry))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = mobileData,
                role = Role.Switch,
                onValueChange = {
                    mobileData = it
                    RecordingSync.setUseMobileData(context, it)
                },
            )
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sync_mobile_data))
            Text(stringResource(R.string.sync_mobile_data_description), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = mobileData, onCheckedChange = null)
    }
}

private fun setAppLanguage(tag: String) {
    val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
    AppCompatDelegate.setApplicationLocales(locales)
}
