package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.HomeUiState
import com.masterofchessstrategy.R
import com.masterofchessstrategy.ui.theme.MocsTheme

@Composable
fun MasterOfChessStrategyApp(
    state: HomeUiState,
    modifier: Modifier = Modifier,
) {
    MocsTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(0.4f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        text = stringResource(R.string.offline_tagline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Card(modifier = Modifier.weight(0.6f)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.foundation_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = state.engineTitle,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = state.engineDetail,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@Preview(
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun MasterOfChessStrategyAppPreview() {
    MasterOfChessStrategyApp(
        state = HomeUiState(
            engineTitle = "原生引擎已就绪",
            engineDetail = "MasterofChessStrategy Engine/1",
        ),
    )
}
