package com.moon.videomerger.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

/**
 * 首页 —— 新建项目 / 历史记录
 */
@Composable
fun HomeScreen(
    onNewProject: (List<Uri>) -> Unit,
    onOpenMerge: (List<Uri>) -> Unit,
    onOpenHistory: () -> Unit
) {
    val context = LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onNewProject(uris)
        }
    }

    // 合并功能专用选择器（至少选 2 个视频，不足时给出提示而非静默忽略）
    val mergePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        when {
            uris.size >= 2 -> onOpenMerge(uris)
            uris.isNotEmpty() -> Toast
                .makeText(context, "视频合并至少需要选择 2 个视频（当前 ${uris.size} 个）", Toast.LENGTH_SHORT)
                .show()
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
                Text("影剪", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Default.History, contentDescription = "历史记录", tint = Color.White)
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
        }
    }
}
