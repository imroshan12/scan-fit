package app.scanfit.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType

/** The languages the app ships (spec/strings). Autonyms are never translated. */
private enum class AppLanguage(
    val tag: String,
    val label: Int,
) {
    SYSTEM("", R.string.language_system),
    ENGLISH("en", R.string.language_en),
    HINDI("hi", R.string.language_hi),
}

@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state, currentLanguageTag = {
        AppCompatDelegate.getApplicationLocales().toLanguageTags()
    }, onLanguageChosen = {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
    }, modifier = modifier)
}

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    currentLanguageTag: () -> String,
    onLanguageChosen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLanguages by rememberSaveable { mutableStateOf(false) }
    val current =
        AppLanguage.entries.firstOrNull { it.tag == currentLanguageTag().substringBefore('-') } ?: AppLanguage.SYSTEM

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = ScanFitSpacing.sm)) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ScanFitSpacing.minTouchTarget)
                .clickable(role = Role.Button) { showLanguages = true }
                .padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stringResource(R.string.settings_language), style = ScanFitType.body)
                Text(
                    stringResource(current.label),
                    style = ScanFitType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.presetsVersion?.let { version ->
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_presets_version, version),
                style = ScanFitType.figure,
                modifier = Modifier.padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.lg),
            )
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.settings_about),
            style = ScanFitType.label,
            color = MaterialTheme.colorScheme.primary,
            modifier =
            Modifier
                .padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.lg)
                .semantics { heading() },
        )
        Text(
            stringResource(R.string.legal_disclaimer),
            style = ScanFitType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScanFitSpacing.screenMargin),
        )
    }

    if (showLanguages) {
        AlertDialog(
            onDismissRequest = { showLanguages = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { language ->
                        Row(
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = ScanFitSpacing.minTouchTarget)
                                .selectable(selected = language == current, role = Role.RadioButton) {
                                    showLanguages = false
                                    onLanguageChosen(language.tag)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == current, onClick = null)
                            Text(stringResource(language.label), modifier = Modifier.padding(start = ScanFitSpacing.md))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguages = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, fontScale = 2f, locale = "hi")
@Composable
private fun SettingsScreenPreview() {
    ScanFitTheme { SettingsScreen(SettingsUiState(presetsVersion = 1), { "" }, {}) }
}
