package com.moon.videomerger.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moon.videomerger.util.VideoHistoryStore

/**
 * 首页 —— 新建项目 / 历史记录
 */
@Composable
fun HomeScreen(
    onNewProject: (List<Uri>) -> Unit,
    onOpenMerge: (List<Uri>) -> Unit,
    onOpenHistory: (String) -> Unit
) {
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onNewProject(uris)
        }
    }

    // 合并功能专用选择器（至少选 2 个视频）
    val mergePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.size >= 2) {
            onOpenMerge(uris)
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("VideoEditor", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.refreshHistory() }) {
                    Icon(Icons.Default.History, contentDescription = "历史", tint = Color.White)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .padding(padding)
        ) {
            // ── 新建项目按钮 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1A1A))
                    .clickable { videoPicker.launch("video/*") },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AddCircle, contentDescription = null, tint = Color(0xFF2196F3), modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("新建项目", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("选择视频开始编辑", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }

            // ── 视频合并按钮 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1A1A))
                    .clickable { mergePicker.launch("video/*") },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.GridView, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("视频合并", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("宫格 / 主次 / 照片墙（至少选2个视频）", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }

            // ── 历史记录 ──
            if (state.history.isNotEmpty()) {
                Text(
                    "历史记录",
                    color = Color(0xFF888888),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.history) { entry ->
                        HistoryCard(
                            entry = entry,
                            onClick = { onOpenHistory(entry.filePath) }
                        )
                    }
                }
            }
        }
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
        Column {
            Text(entry.mergeType, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${entry.width}x${entry.height} · ${"%.1f".format(entry.duration)}s", color = Color(0xFF888888), fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
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
