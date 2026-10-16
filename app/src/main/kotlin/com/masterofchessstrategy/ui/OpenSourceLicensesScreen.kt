package com.masterofchessstrategy.ui

import android.text.Html
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val OPEN_SOURCE_LICENSES_SCREEN_TAG = "open_source_licenses_screen"
internal const val KATAGO_SOURCE_EXPORT_TAG = "katago_source_export"
internal const val KATAGO_SOURCE_EXPORT_STATUS_TAG = "katago_source_export_status"
internal const val KATAGO_DEPENDENCY_SOURCE_ASSET = "games/go/legal/dependency-source.zip"

internal data class LegalDocuments(
    val notices: String,
    val gpl: String,
    val network: String,
    val kataGo: String = "",
)

private enum class LegalDocument {
    NOTICES,
    GPL,
    NETWORK,
    KATAGO,
}

/** Reads the exact legal documents bundled in the installed APK. */
@Composable
internal fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val assets = context.assets
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exportInProgress by remember { mutableStateOf(false) }
    val exportSuccess = stringResource(R.string.open_source_katago_source_exported)
    val exportFailure = stringResource(R.string.open_source_katago_source_export_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        if (destination != null) {
            exportInProgress = true
            exportStatus = null
            scope.launch {
                val succeeded = runCatching {
                    withContext(Dispatchers.IO) {
                        copyLegalAsset(
                            openSource = { assets.open(KATAGO_DEPENDENCY_SOURCE_ASSET) },
                            openDestination = {
                                context.contentResolver.openOutputStream(destination, "w")
                            },
                        )
                    }
                }.isSuccess
                exportInProgress = false
                exportStatus = if (succeeded) exportSuccess else exportFailure
            }
        }
    }
    val documents = remember(assets) {
        LegalDocuments(
            notices = assets.readUtf8Asset("legal/OPEN-SOURCE-NOTICES.md"),
            gpl = assets.readUtf8Asset("legal/GPL-3.0.txt"),
            network = assets.readUtf8Asset("pikafish/NETWORK-LICENSE.md"),
            kataGo = assets.readKataGoLegalBundle(),
        )
    }
    OpenSourceLicensesContent(
        documents = documents,
        onBack = onBack,
        modifier = modifier,
        exportInProgress = exportInProgress,
        exportStatus = exportStatus,
        onSaveKataGoSource = {
            exportLauncher.launch("MasterofChessStrategy-KataGo-dependency-source.zip")
        },
    )
}

@Composable
internal fun OpenSourceLicensesContent(
    documents: LegalDocuments,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    exportInProgress: Boolean = false,
    exportStatus: String? = null,
    onSaveKataGoSource: () -> Unit = {},
) {
    var selected by remember { mutableStateOf(LegalDocument.NOTICES) }
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(OPEN_SOURCE_LICENSES_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.open_source_licenses_title),
                subtitle = stringResource(R.string.open_source_licenses_subtitle),
                onBack = onBack,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.open_source_copyright),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.open_source_warranty_notice),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.open_source_source_offer),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.open_source_network_boundary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.open_source_katago_boundary),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LegalDocument.entries.forEach { document ->
                    FilterChip(
                        selected = selected == document,
                        onClick = { selected = document },
                        label = { Text(stringResource(document.titleResource())) },
                    )
                }
            }
            if (selected == LegalDocument.KATAGO) {
                Button(
                    onClick = onSaveKataGoSource,
                    enabled = !exportInProgress,
                    modifier = Modifier.testTag(KATAGO_SOURCE_EXPORT_TAG),
                ) {
                    Text(
                        stringResource(
                            if (exportInProgress) R.string.open_source_katago_source_exporting
                            else R.string.open_source_katago_source_export,
                        ),
                    )
                }
                exportStatus?.let {
                    Text(
                        text = it,
                        modifier = Modifier.testTag(KATAGO_SOURCE_EXPORT_STATUS_TAG),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                text = when (selected) {
                    LegalDocument.NOTICES -> documents.notices
                    LegalDocument.GPL -> documents.gpl
                    LegalDocument.NETWORK -> documents.network
                    LegalDocument.KATAGO -> documents.kataGo
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun android.content.res.AssetManager.readUtf8Asset(path: String): String =
    open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

private fun android.content.res.AssetManager.readKataGoLegalBundle(): String = buildString {
    appendLine("KataGo v1.18.1 · 92ee95c0a4b25fec214da00951ab69e97e207729")
    appendLine()
    appendLine(readUtf8Asset("games/go/legal/KataGo-LICENSE.txt"))
    appendLine("--- CONTRIBUTORS ---")
    appendLine(readUtf8Asset("games/go/legal/KataGo-CONTRIBUTORS.txt"))
    appendLine("--- KATAGO NEURAL NETWORK LICENSE ---")
    appendLine(
        Html.fromHtml(
            readUtf8Asset("games/go/legal/KataGo-network-license.html"),
            Html.FROM_HTML_MODE_LEGACY,
        ).toString().trim(),
    )
    appendLine()
    appendLine("--- FILESYSTEM-1.5.8 ---")
    appendLine(readUtf8Asset("games/go/legal/filesystem-LICENSE.txt"))
    appendLine("--- EIGEN 3.4.0 ---")
    appendLine(readUtf8Asset("games/go/legal/Eigen-COPYING.README"))
    appendLine(readUtf8Asset("games/go/legal/Eigen-COPYING.MPL2"))
    appendLine("Additional nlohmann JSON and SHA-2 notices are retained in the bundled source files and dependency-source.zip.")
}

internal fun copyLegalAsset(
    openSource: () -> InputStream,
    openDestination: () -> OutputStream?,
) {
    openSource().use { input ->
        requireNotNull(openDestination()) { "Destination could not be opened" }.use { output ->
            input.copyTo(output)
            output.flush()
        }
    }
}

private fun LegalDocument.titleResource(): Int =
    when (this) {
        LegalDocument.NOTICES -> R.string.open_source_notices_tab
        LegalDocument.GPL -> R.string.open_source_gpl_tab
        LegalDocument.NETWORK -> R.string.open_source_network_tab
        LegalDocument.KATAGO -> R.string.open_source_katago_tab
    }
