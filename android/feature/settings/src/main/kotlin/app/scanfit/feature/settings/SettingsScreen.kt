package app.scanfit.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.config.AppLinks
import app.scanfit.core.data.quota.QuotaStatus
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

internal class SettingsActions(
    val currentLanguageTag: () -> String = { "" },
    val onLanguageChosen: (String) -> Unit = {},
    val onShowUnverifiedChange: (Boolean) -> Unit = {},
    val onAnalyticsChange: (Boolean) -> Unit = {},
    val onGetPro: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    /** Opens the user's email app; `false` when there is none. */
    val onReportProblem: () -> Boolean = { true },
)

@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val appVersion = remember(context) { appVersion(context) }
    SettingsScreen(
        state,
        appVersion,
        SettingsActions(
            currentLanguageTag = { AppCompatDelegate.getApplicationLocales().toLanguageTags() },
            onLanguageChosen = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) },
            onShowUnverifiedChange = viewModel::onShowUnverifiedChange,
            onAnalyticsChange = viewModel::onAnalyticsChange,
            onGetPro = viewModel::onGetPro,
            onRestore = viewModel::onRestore,
            onOpenLink = { uri.openUri(it) },
            onReportProblem = { reportProblem(context, appVersion, state.presetsVersion) },
        ),
        modifier,
    )
}

/** "Report a problem" (ROADMAP Phase 3): an email with the app, system and specs versions, never a file. */
private fun reportProblem(
    context: Context,
    appVersion: String,
    presetsVersion: Int?,
): Boolean {
    val body = context.getString(
        R.string.settings_report_body,
        appVersion,
        "Android ${Build.VERSION.RELEASE}",
        presetsVersion?.toString() ?: "-",
    )
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(AppLinks.SUPPORT_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_report_subject))
        .putExtra(Intent.EXTRA_TEXT, body)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun appVersion(context: Context): String = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName.orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    ""
}

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    var showLanguages by rememberSaveable { mutableStateOf(false) }
    var noEmail by rememberSaveable { mutableStateOf(false) }
    val current =
        AppLanguage.entries.firstOrNull { it.tag == actions.currentLanguageTag().substringBefore('-') }
            ?: AppLanguage.SYSTEM

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = ScanFitSpacing.xl)) {
        ProSection(state, actions)

        SectionHeader(stringResource(R.string.tab_settings))
        LinkRow(stringResource(R.string.settings_language), stringResource(current.label)) { showLanguages = true }
        HorizontalDivider()
        SwitchRow(
            stringResource(R.string.settings_show_unverified),
            stringResource(R.string.settings_show_unverified_hint),
            state.showUnverified,
            actions.onShowUnverifiedChange,
        )
        state.presetsVersion?.let { Note(stringResource(R.string.settings_presets_version, it)) }

        SectionHeader(stringResource(R.string.settings_privacy))
        Note(stringResource(R.string.settings_privacy_note))
        SwitchRow(
            stringResource(R.string.settings_analytics),
            stringResource(R.string.settings_analytics_hint),
            state.analyticsEnabled,
            actions.onAnalyticsChange,
        )
        HorizontalDivider()
        LinkRow(stringResource(R.string.legal_privacy_policy)) { actions.onOpenLink(AppLinks.PRIVACY_POLICY) }
        HorizontalDivider()
        LinkRow(stringResource(R.string.legal_terms)) { actions.onOpenLink(AppLinks.TERMS) }

        SectionHeader(stringResource(R.string.settings_help))
        LinkRow(stringResource(R.string.settings_help)) { actions.onOpenLink(AppLinks.HELP) }
        HorizontalDivider()
        LinkRow(stringResource(R.string.settings_report_problem)) { noEmail = !actions.onReportProblem() }
        if (noEmail) Note(stringResource(R.string.settings_no_email, AppLinks.SUPPORT_EMAIL))

        SectionHeader(stringResource(R.string.settings_about))
        Note(stringResource(R.string.legal_disclaimer))
        if (appVersion.isNotEmpty()) Note(stringResource(R.string.settings_version, appVersion))
    }

    if (showLanguages) {
        LanguageDialog(current, onChosen = {
            showLanguages = false
            actions.onLanguageChosen(it.tag)
        }, onDismiss = { showLanguages = false })
    }
}

/** Pro status, free saves left, Get Pro and Restore purchases (ALGORITHMS 14.3). */
@Composable
private fun ProSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SectionHeader(stringResource(R.string.settings_pro))
    val quota = state.quota
    when {
        quota == null -> Unit

        quota.pro -> Note(stringResource(R.string.settings_pro_active))

        else -> {
            Text(
                if (quota.remaining > 0) {
                    pluralStringResource(R.plurals.quota_left, quota.remaining, quota.remaining)
                } else {
                    stringResource(R.string.quota_none)
                },
                style = ScanFitType.body,
                modifier = Modifier.padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.sm),
            )
            Button(
                onClick = actions.onGetPro,
                modifier =
                Modifier
                    .padding(horizontal = ScanFitSpacing.screenMargin)
                    .fillMaxWidth()
                    .heightIn(min = ScanFitSpacing.minTouchTarget),
            ) { Text(stringResource(R.string.settings_get_pro)) }
        }
    }
    LinkRow(stringResource(R.string.paywall_restore)) {
        if (state.restore != RestoreState.RESTORING) actions.onRestore()
    }
    when (state.restore) {
        RestoreState.RESTORED -> Note(stringResource(R.string.paywall_thanks))
        RestoreState.NOTHING -> Note(stringResource(R.string.paywall_restore_none))
        RestoreState.FAILED -> Note(stringResource(R.string.paywall_unavailable))
        RestoreState.IDLE, RestoreState.RESTORING -> Unit
    }
}

@Composable
private fun LanguageDialog(
    current: AppLanguage,
    onChosen: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                AppLanguage.entries.forEach { language ->
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = ScanFitSpacing.minTouchTarget)
                            .selectable(selected = language == current, role = Role.RadioButton) { onChosen(language) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == current, onClick = null)
                        Text(stringResource(language.label), modifier = Modifier.padding(start = ScanFitSpacing.md))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Preview(showBackground = true, heightDp = 1400)
@Preview(showBackground = true, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, heightDp = 2600, fontScale = 2f, locale = "hi")
@Composable
private fun SettingsFreePreview() {
    ScanFitTheme {
        SettingsScreen(
            SettingsUiState(presetsVersion = 4, quota = QuotaStatus(pro = false, remaining = 3, showsLeft = true)),
            "0.9.0",
            SettingsActions(),
        )
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsProPreview() {
    ScanFitTheme {
        SettingsScreen(
            SettingsUiState(
                presetsVersion = 4,
                quota = QuotaStatus(pro = true, remaining = 0, showsLeft = false),
                analyticsEnabled = false,
                restore = RestoreState.RESTORED,
            ),
            "0.9.0",
            SettingsActions(),
        )
    }
}
