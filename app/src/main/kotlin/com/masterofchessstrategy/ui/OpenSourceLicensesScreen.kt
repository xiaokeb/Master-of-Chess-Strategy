package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R

internal const val OPEN_SOURCE_LICENSES_SCREEN_TAG = "open_source_licenses_screen"

internal data class LegalDocuments(
    val notices: String,
    val gpl: String,
    val network: String,
)

private enum class LegalDocument {
    NOTICES,
    GPL,
    NETWORK,
}

/** Reads the exact legal documents bundled in the installed APK. */
@Composable
internal fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val assets = LocalContext.current.assets
    val documents = remember(assets) {
        LegalDocuments(
            notices = assets.readUtf8Asset("legal/OPEN-SOURCE-NOTICES.md"),
            gpl = assets.readUtf8Asset("legal/GPL-3.0.txt"),
            network = assets.readUtf8Asset("pikafish/NETWORK-LICENSE.md"),
        )
    }
    OpenSourceLicensesContent(documents, onBack, modifier)
}

@Composable
internal fun OpenSourceLicensesContent(
    documents: LegalDocuments,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
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
            Text(
                text = when (selected) {
                    LegalDocument.NOTICES -> documents.notices
                    LegalDocument.GPL -> documents.gpl
                    LegalDocument.NETWORK -> documents.network
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun android.content.res.AssetManager.readUtf8Asset(path: String): String =
    open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

private fun LegalDocument.titleResource(): Int =
    when (this) {
        LegalDocument.NOTICES -> R.string.open_source_notices_tab
        LegalDocument.GPL -> R.string.open_source_gpl_tab
        LegalDocument.NETWORK -> R.string.open_source_network_tab
    }
