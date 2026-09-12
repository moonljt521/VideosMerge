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
import com.moon.videomerger.editor.data.DraftStore

/**
 * 首页 —— 新建项目 / 视频合并；草稿从右上角图标进入。
 */
@Composable
fun HomeScreen(
    onNewProject: (List<Uri>) -> Unit,
    onOpenMerge: (List<Uri>) -> Unit,
    onOpenDouyin: () -> Unit,
    onOpenGif: () -> Unit,
    onOpenHistory: () -> Unit,
    draftInfo: DraftStore.DraftInfo?,
    onResumeDraft: () -> Unit,
    onDeleteDraft: () -> Unit
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

    // ★ 已有草稿时，新建项目会覆盖草稿 → 二次确认
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var showDraftDialog by remember { mutableStateOf(false) }
    if (showOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { showOverwriteDialog = false },
            title = { Text("覆盖现有草稿？", color = Color.White) },
            text = {
                Text(
                    "当前有未导出的草稿「${draftInfo?.name ?: ""}」，\n新建项目将自动覆盖它。可先继续编辑或删除草稿。",
                    color = Color(0xFFBBBBBB)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showOverwriteDialog = false
                    videoPicker.launch("video/*")
                }) { Text("仍要新建", color = Color(0xFFFF7043)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverwriteDialog = false
                    onResumeDraft()
                }) { Text("继续编辑草稿", color = Color(0xFF2196F3)) }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
    // 草稿弹窗（右上角图标进入）：继续编辑 / 删除
    if (showDraftDialog) {
        AlertDialog(
            onDismissRequest = { showDraftDialog = false },
            title = { Text("草稿", color = Color.White) },
            text = {
                val parts = mutableListOf<String>()
                parts.add(draftInfo?.name ?: "")
                draftInfo?.clipCount?.takeIf { it > 0 }?.let { parts.add("$it 个片段") }
                draftInfo?.subtitleCount?.takeIf { it > 0 }?.let { parts.add("$it 条字幕") }
                Text(parts.joinToString("\n"), color = Color(0xFFBBBBBB))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDraftDialog = false
                    onResumeDraft()
                }) { Text("继续编辑", color = Color(0xFF2196F3)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDraftDialog = false
                    onDeleteDraft()
                }) { Text("删除草稿", color = Color(0xFFD32F2F)) }
            },
            containerColor = Color(0xFF1E1E1E)
        )
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
                // ★ 草稿入口（右上角，有草稿时显示红点角标）
                if (draftInfo != null) {
                    IconButton(onClick = { showDraftDialog = true }) {
                        Box {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = "草稿",
                                tint = Color.White
                            )
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(Color(0xFFFF5252), androidx.compose.foundation.shape.CircleShape)
                                    .align(Alignment.TopEnd)
                            )
                        }
                    }
                }
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
                    .clickable {
                        if (draftInfo != null) showOverwriteDialog = true else videoPicker.launch("video/*")
                    },
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

            // ── 抖音去水印按钮 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1A1A))
                    .clickable { onOpenDouyin() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.WaterDrop, contentDescription = null, tint = Color(0xFF26C6DA), modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("短视频去水印", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("粘贴分享文案，解析无水印视频并保存", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }

            // ── 视频转GIF按钮 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1A1A))
                    .clickable { onOpenGif() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Gif, contentDescription = null, tint = Color(0xFFFFA726), modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("视频转GIF", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("选择视频，一键转为 GIF 动图并保存", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }
        }
    }
}
