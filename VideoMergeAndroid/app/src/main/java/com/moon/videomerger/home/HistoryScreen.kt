package com.moon.videomerger.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moon.videomerger.ui.FullscreenVideoPlayer
import com.moon.videomerger.util.MediaUtils
import com.moon.videomerger.util.VideoHistoryStore
import java.io.File

/**
 * 历史记录页 —— 独立的视频历史列表。
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit
) {
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var playingPath by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (playingPath != null) playingPath = null else onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text("历史记录", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.refreshHistory() }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color.White)
            }
        }

        if (state.history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无历史记录", color = Color(0xFF888888), fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.history) { entry ->
                    HistoryCard(
                        entry = entry,
                        onClick = { playingPath = entry.filePath }
                    )
                }
            }
        }
    }

    playingPath?.let { path ->
        FullscreenVideoPlayer(
            videoPath = path,
            onSaveClick = {
                val file = File(path)
                if (file.exists()) {
                    MediaUtils.saveToGallery(
                        context = context,
                        file = file,
                        displayName = "影剪_${System.currentTimeMillis()}.mp4"
                    )
                    Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { playingPath = null }
        )
    }
}

@Composable
private fun HistoryCard(
    entry: VideoHistoryStore.HistoryEntry,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color(0xFF2196F3), modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.mergeType, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${entry.width}x${entry.height} · ${"%.1f".format(entry.duration)}s", color = Color(0xFF888888), fontSize = 12.sp)
        }
        Text(formatDate(entry.timestamp), color = Color(0xFF666666), fontSize = 11.sp)
    }
}

private fun formatDate(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        else -> "${diff / 86_400_000}天前"
    }
}
