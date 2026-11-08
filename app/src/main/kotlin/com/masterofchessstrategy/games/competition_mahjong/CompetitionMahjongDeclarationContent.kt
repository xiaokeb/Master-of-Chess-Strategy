package com.masterofchessstrategy.games.competition_mahjong

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The choice row keeps its height; the complete competition warning scrolls separately. */
@Composable
internal fun CompetitionMahjongDeclarationContent(
    tiles: List<Int>,
    selectedTile: Int,
    onSelected: (Int) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("${tagPrefix}_content"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("${tagPrefix}_choices"),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tiles.forEach { tile ->
                val requester = remember(tile) { BringIntoViewRequester() }
                FilterChip(selected = selectedTile == tile, onClick = { onSelected(tile) },
                    label = { Text(competitionMahjongTileName(tile)) },
                    modifier = Modifier.bringIntoViewRequester(requester).testTag("${tagPrefix}_tile_$tile"))
                LaunchedEffect(selectedTile == tile, requester) {
                    if (selectedTile == tile) {
                        withFrameNanos { }
                        requester.bringIntoView()
                    }
                }
            }
        }
        Text("请选择所和牌并核对八分门槛，花牌不能补足起和分。" +
            "不足八分或错认牌向其他三家各付10分，未听报和各付20分，且本盘停和。",
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                .verticalScroll(rememberScrollState()).testTag("${tagPrefix}_warning"))
    }
}
