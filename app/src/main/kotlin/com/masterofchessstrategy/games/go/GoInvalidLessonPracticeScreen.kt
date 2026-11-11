package com.masterofchessstrategy.games.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun GoInvalidLessonPracticeScreen(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("无法打开对应练习", style = MaterialTheme.typography.titleLarge)
            Text("知识点与残局参数无效或不匹配，原课程和残局进度均未更改。",
                Modifier.testTag("go_lesson_practice_invalid"))
            TextButton(onClick = onBack, modifier = Modifier.testTag("go_lesson_practice_invalid_back")) {
                Text("返回知识点")
            }
        }
    }
}
