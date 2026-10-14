package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ai.closepaw.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.closepaw.BuildConfig
import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.platform.AppManager
import ai.closepaw.protocol.AppTier
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.tool.AppClassifier
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMastheadIdentity
import ai.closepaw.ui.theme.SectionHeader
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsHomePage(
    llmBackend: LLMBackendType,
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    selectedLocalModel: String,
    modelCatalog: ModelCatalog,
    perceptionMode: String,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    debugMode: Boolean,
    platformMode: PlatformMode,
    effectivePlatformMode: PlatformMode?,
    appClassifier: AppClassifier,
    approvalMode: ApprovalMode,
    onNavigate: (SettingsPage) -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PageMastheadIdentity(title = stringResource(R.string.settings_home_page_title), onClose = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.closePaw.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm)
        ) {
            SectionHeader(stringResource(R.string.settings_home_page_section_behavior))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_home_page_llm_auth),
                subtitle = llmSubtitle(llmBackend, selectedModel, modelOptions, selectedLocalModel, modelCatalog),
                onClick = { onNavigate(SettingsPage.LLM_AUTH) }
            )
            SettingsNavigationRow(
                title = stringResource(R.string.settings_home_page_agent_behavior),
                subtitle = agentBehaviorSubtitle(perceptionMode, platformMode, effectivePlatformMode, approvalMode),
                onClick = { onNavigate(SettingsPage.AGENT_BEHAVIOR) }
            )
            SettingsNavigationRow(
                title = stringResource(R.string.settings_home_page_memory),
                subtitle = stringResource(R.string.settings_home_page_memory_subtitle),
                onClick = { onNavigate(SettingsPage.MEMORY) }
            )

            SectionHeader(stringResource(R.string.settings_home_page_section_access))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_home_page_app_access),
                subtitle = appAccessSubtitle(appClassifier, approvalMode),
                onClick = { onNavigate(SettingsPage.APP_ACCESS) }
            )
            SettingsNavigationRow(
                title = stringResource(R.string.settings_home_page_system_debug),
                subtitle = permissionsSubtitle(isAccessibilityEnabled, isOverlayEnabled, debugMode),
                onClick = { onNavigate(SettingsPage.PERMISSIONS_ADVANCED) }
            )

            SectionHeader(stringResource(R.string.settings_home_page_section_about))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_home_page_licenses),
                subtitle = stringResource(R.string.settings_home_page_licenses_subtitle),
                onClick = { onNavigate(SettingsPage.OPEN_SOURCE_LICENSES) }
            )

            Fleuron()

            Text(
                text = stringResource(R.string.settings_home_page_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.closePaw.monoSmall,
                color = MaterialTheme.closePaw.inkFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun llmSubtitle(
    llmBackend: LLMBackendType,
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    selectedLocalModel: String,
    modelCatalog: ModelCatalog,
): String = if (llmBackend == LLMBackendType.LOCAL) {
    AVAILABLE_LOCAL_MODELS.find { it.id == selectedLocalModel }?.localizedDisplayName() ?: selectedLocalModel
} else {
    val modelName = modelOptions.find { it.first == selectedModel }?.second ?: selectedModel
    val mode = modelCatalog.resolveOrNull(selectedModel)?.provider?.mode
    val authLabel = when (mode) {
        AuthMode.OAuth -> "OAuth"
        AuthMode.ApiKey -> stringResource(R.string.settings_home_page_auth_api_key)
        AuthMode.Local, null -> stringResource(R.string.settings_home_page_auth_api_key)
    }
    "$modelName · $authLabel"
}

@Composable
private fun agentBehaviorSubtitle(
    perceptionMode: String,
    platformMode: PlatformMode,
    effectivePlatformMode: PlatformMode?,
    approvalMode: ApprovalMode,
): String {
    val approvalChip = approvalModeLabel(approvalMode)
    val perceptionChip = when (perceptionMode) {
        "hybrid" -> stringResource(R.string.settings_home_page_perception_hybrid)
        "screenshot_only" -> stringResource(R.string.settings_home_page_perception_image)
        else -> stringResource(R.string.settings_home_page_perception_transcript)
    }
    val displayChip = when (platformMode) {
        PlatformMode.ACCESSIBILITY -> when (effectivePlatformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> " · " + stringResource(R.string.settings_home_page_vd_this_session)
            else -> ""
        }
        PlatformMode.VIRTUAL_DISPLAY -> when (effectivePlatformMode) {
            PlatformMode.ACCESSIBILITY -> " · " + stringResource(R.string.settings_home_page_vd_next_session)
            else -> " · " + stringResource(R.string.settings_home_page_vd)
        }
    }
    return "$approvalChip · $perceptionChip$displayChip"
}

@Composable
private fun approvalModeLabel(approvalMode: ApprovalMode): String = when (approvalMode) {
    ApprovalMode.SMART -> stringResource(R.string.settings_home_page_approval_per_app)
    ApprovalMode.AUTO_APPROVE -> stringResource(R.string.settings_home_page_approval_auto)
    ApprovalMode.ALWAYS_ASK -> stringResource(R.string.settings_home_page_approval_always_ask)
}

@Composable
private fun permissionsSubtitle(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    debugMode: Boolean,
): String {
    val grantedCount = listOf(isAccessibilityEnabled, isOverlayEnabled).count { it }
    val permSummary = when (grantedCount) {
        2 -> stringResource(R.string.settings_home_page_perms_all_granted)
        1 -> stringResource(R.string.settings_home_page_perms_one_granted)
        else -> stringResource(R.string.settings_home_page_perms_setup_required)
    }
    val debugLabel = stringResource(if (debugMode) R.string.settings_home_page_debug_on else R.string.settings_home_page_debug_off)
    return "$permSummary · $debugLabel"
}

/**
 * Subtitle for the App Access entry on the Settings home page.
 *
 * Counts installed apps per effective tier (allow / ask / reject). The installed
 * app set is effectively constant, but tier classification depends on the live
 * `userOverrides` map, so we re-scan when overrides change. While the IO scan
 * is in flight, render `…` rather than block.
 */
@Composable
private fun appAccessSubtitle(classifier: AppClassifier, approvalMode: ApprovalMode): String {
    val context = LocalContext.current
    val overrides by classifier.userOverrides.collectAsState()
    val counts by produceState<Triple<Int, Int, Int>?>(
        initialValue = null, context, classifier, overrides,
    ) {
        value = withContext(Dispatchers.IO) {
            var allow = 0
            var ask = 0
            var reject = 0
            AppManager.getInstalledApps(context.packageManager).forEach { info ->
                when (classifier.classify(info.packageName)) {
                    AppTier.NORMAL -> allow++
                    AppTier.CAUTIOUS -> ask++
                    AppTier.BLOCKED -> reject++
                }
            }
            Triple(allow, ask, reject)
        }
    }
    val modeLabel = approvalModeLabel(approvalMode)
    val loaded = counts
    return if (loaded != null) {
        stringResource(R.string.settings_home_page_app_access_counts, modeLabel, loaded.first, loaded.second, loaded.third)
    } else {
        "$modeLabel · …"
    }
}
