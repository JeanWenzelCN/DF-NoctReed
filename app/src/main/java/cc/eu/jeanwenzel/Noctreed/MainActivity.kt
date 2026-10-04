package cc.eu.jeanwenzel.Noctreed

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.eu.jeanwenzel.Noctreed.data.CalibrationStore
import cc.eu.jeanwenzel.Noctreed.data.ScoreStore
import cc.eu.jeanwenzel.Noctreed.score.MidiImporter
import cc.eu.jeanwenzel.Noctreed.score.ScoreParser
import cc.eu.jeanwenzel.Noctreed.service.HarmonicaAccessibilityService
import cc.eu.jeanwenzel.Noctreed.service.OverlayController
import cc.eu.jeanwenzel.Noctreed.service.OverlayService
import cc.eu.jeanwenzel.Noctreed.service.PlayState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NoctReedTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun NoctReedTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // 固定浅色 + Material You 莫奈动态取色（minSdk 31+ 原生支持）
    val scheme = dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(HarmonicaAccessibilityService.isEnabled()) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val store = remember { CalibrationStore(context) }
    var calibrated by remember { mutableStateOf(store.allCalibrated()) }

    val scoreStore = remember { ScoreStore(context) }
    var savedScores by remember { mutableStateOf(scoreStore.list()) }

    var scoreText by remember { mutableStateOf(ScoreParser.SAMPLE) }
    var bpmText by remember { mutableStateOf("90") }
    // 编辑 BPM 时不允许轮询回写（否则输入 "1" 会被 coerceIn(30,300) 后强制改成 "30"）
    var bpmEditing by remember { mutableStateOf(false) }
    val playState by OverlayController.playState.collectAsState()
    val playing = playState != PlayState.IDLE

    // 当前编辑中的曲目名（载入/保存/重命名共用）
    var scoreName by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ScoreStore.SavedScore?>(null) }
    var nameInput by remember { mutableStateOf("") }
    // 导入失败弹窗（Toast 两行截断显示不全，改用弹窗展示完整原因）
    var importError by remember { mutableStateOf<String?>(null) }
    // 独立乐谱库界面开关
    var showLibrary by remember { mutableStateOf(false) }

    // txt / midi 导入（SAF）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val isMidi = uri.toString().endsWith(".mid", true) ||
                    uri.toString().endsWith(".midi", true) ||
                    context.contentResolver.getType(uri)?.contains("midi") == true
                if (isMidi) {
                    val result = MidiImporter.parse(input)
                    Triple(result.jianpu, result.bpm, "MIDI（${result.noteCount} 个音符）")
                } else {
                    Triple(input.bufferedReader().readText().trim(), null, "txt")
                }
            } ?: throw Exception("无法读取文件")
        }.onSuccess { (text, midiBpm, source) ->
            if (text.isNotBlank()) {
                scoreText = text
                if (midiBpm != null) bpmText = midiBpm.toString()
                scoreName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
                OverlayController.updateScore(scoreText)
                midiBpm?.let(OverlayController::updateBpm)
                Toast.makeText(context, "已从 $source 导入", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            importError = it.message ?: "未知错误"
        }
    }

    // 回到前台时刷新权限状态，并同步悬浮窗内可能已切换的乐谱
    LaunchedEffect(Unit) {
        OverlayController.updateScore(scoreText)
        while (true) {
            accessibilityOn = HarmonicaAccessibilityService.isEnabled()
            overlayOn = Settings.canDrawOverlays(context)
            calibrated = store.allCalibrated()
            // 悬浮窗内切换乐谱后，回到主界面时同步显示
            val ctrlScore = OverlayController.score.value
            if (ctrlScore != scoreText) scoreText = ctrlScore
            val ctrlBpm = OverlayController.bpm.value
            if (!bpmEditing && ctrlBpm.toString() != bpmText) bpmText = ctrlBpm.toString()
            val ctrlName = OverlayController.scoreName.value
            if (ctrlName != scoreName) scoreName = ctrlName
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "NoctReed",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "守夜人口琴 · 自动演奏",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // —— 第一步：无障碍 ——
        GuideCard(
            step = "第 1 步",
            title = "开启无障碍服务",
            done = accessibilityOn,
            description = "在系统设置中找到「NoctReed」并开启。"
        ) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("去开启") }
        }

        // —— 第二步：悬浮窗 ——
        GuideCard(
            step = "第 2 步",
            title = "允许悬浮窗",
            done = overlayOn,
            description = "允许在游戏上层显示控制面板。"
        ) {
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"))
                )
            }) { Text("去授权") }
        }

        // —— 第三步：校准（引导式，不再点击即开始）——
        GuideCard(
            step = "第 3 步",
            title = "按键校准",
            done = calibrated,
            description = "横屏进入游戏口琴界面后，点悬浮窗「校准」，按提示依次点击 12 个按键。",
        ) {
            Button(
                enabled = overlayOn,
                onClick = {
                    OverlayService.start(context)
                    Toast.makeText(
                        context,
                        "悬浮窗已启动，请在横屏界面点「校准」",
                        Toast.LENGTH_LONG
                    ).show()
                }
            ) { Text(if (calibrated) "重新校准" else "启动校准引导") }
        }

        // —— 乐谱与演奏 ——
        ElevatedCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.PlayArrow, contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("乐谱演奏", style = MaterialTheme.typography.titleMedium)
                Text(
                    "支持数字简谱（含高低音点、升降号、延音线），可直接粘贴或从乐谱库 / MIDI 导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = scoreText,
                    onValueChange = { scoreText = it; OverlayController.updateScore(it) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("乐谱") }
                )
                OutlinedTextField(
                    value = bpmText,
                    onValueChange = {
                        bpmText = it.filter(Char::isDigit).take(3)
                        bpmText.toIntOrNull()?.let { v -> if (v >= 30) OverlayController.updateBpm(v) }
                    },
                    modifier = Modifier.onFocusChanged { f ->
                        if (f.isFocused) bpmEditing = true
                        else {
                            bpmEditing = false
                            // 失焦时同步一次（钳位后的控制器值为准）
                            bpmText = OverlayController.bpm.value.toString()
                        }
                    },
                    label = { Text("BPM（30–300，悬浮窗可实时调整）") },
                    singleLine = true
                )

                // 导入 + 保存
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("*/*"))
                    }) { Text("导入乐谱 / MIDI") }
                    OutlinedButton(
                        enabled = scoreText.isNotBlank(),
                        onClick = {
                            nameInput = if (scoreName.isNotBlank()) scoreName
                                else "乐谱 ${savedScores.size + 1}"
                            showSaveDialog = true
                        }
                    ) { Text("保存乐谱") }
                }

                // 乐谱库入口：跳转独立界面
                OutlinedButton(
                    onClick = { showLibrary = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painterResource(R.drawable.ic_library),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (scoreName.isNotBlank()) scoreName else "我的乐谱",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (savedScores.isNotEmpty()) {
                        Text(
                            text = "${savedScores.size} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        painterResource(R.drawable.ic_expand),
                        contentDescription = "展开乐谱库",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 演奏控制
                if (!accessibilityOn) {
                    Text(
                        "缺少无障碍权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = accessibilityOn && calibrated && scoreText.isNotBlank() && !playing,
                        onClick = {
                            OverlayController.updateScore(scoreText)
                            OverlayController.updateScoreName(scoreName.ifBlank { "未命名乐谱" })
                            OverlayController.start(context)
                        }
                    ) { Text("开始") }
                    OutlinedButton(
                        enabled = playing,
                        onClick = {
                            if (playState == PlayState.PLAYING) OverlayController.pause()
                            else OverlayController.resume()
                        }
                    ) { Text(if (playState == PlayState.PAUSED) "继续" else "暂停") }
                    OutlinedButton(
                        enabled = playing,
                        onClick = { OverlayController.stop() }
                    ) { Text("停止") }
                }

                OutlinedButton(
                    enabled = overlayOn,
                    onClick = { OverlayService.start(context) }
                ) { Text("显示悬浮窗") }
                }
            }
        }
        // —— 说明 ——
        ElevatedCard {
            Row(Modifier.padding(16.dp)) {
                Icon(Icons.Default.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "流程：开启无障碍 → 授权悬浮窗 → 横屏校准 → 悬浮窗演奏。\n\n" +
                        "MIDI 导入：单旋律、无和弦，音域 C4—♯C7。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // —— 项目仓库（独立区块，置底） ——
        Spacer(Modifier.height(16.dp))
        ElevatedCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.ic_library), contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("项目仓库", style = MaterialTheme.typography.titleSmall)
                    Text("源代码、更新与问题反馈请访问 GitHub。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/JeanWenzelCN/DF-NoctReed"))) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("打开 GitHub 仓库") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    // —— 导入失败弹窗 ——
    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("导入失败") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("知道了") }
            }
        )
    }

    // —— 独立乐谱库界面 ——
    if (showLibrary) {
        AlertDialog(
            onDismissRequest = { showLibrary = false },
            title = { Text("乐谱库（${savedScores.size}）") },
            text = {
                if (savedScores.isEmpty()) {
                    Text("暂无已保存乐谱，请先保存。")
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        savedScores.forEach { s ->
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${s.bpm} BPM · ${s.text.take(40)}${if (s.text.length > 40) "…" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            scoreText = s.text
                                            bpmText = s.bpm.toString()
                                            scoreName = s.name
                                            OverlayController.updateScore(s.text)
                                            OverlayController.updateBpm(s.bpm)
                                            OverlayController.updateScoreName(s.name)
                                            showLibrary = false
                                        }) { Text("载入") }
                                        TextButton(onClick = {
                                            renameTarget = s
                                            nameInput = s.name
                                            showLibrary = false
                                        }) { Text("重命名") }
                                        TextButton(onClick = {
                                            scoreStore.delete(s.name)
                                            savedScores = scoreStore.list()
                                        }) {
                                            Text("删除", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLibrary = false }) { Text("关闭") }
            }
        )
    }

    // —— 保存乐谱命名对话框 ——
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存乐谱") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("曲名") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = nameInput.trim()
                    if (name.isEmpty()) {
                        Toast.makeText(context, "曲名不能为空", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    scoreStore.save(name, scoreText, bpmText.toIntOrNull() ?: 90)
                    savedScores = scoreStore.list()
                    scoreName = name
                    OverlayController.updateScoreName(name)
                    showSaveDialog = false
                    Toast.makeText(context, "已保存为「$name」", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            }
        )
    }

    // —— 重命名对话框 ——
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名乐谱") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("新曲名") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = nameInput.trim()
                    if (newName.isEmpty()) {
                        Toast.makeText(context, "曲名不能为空", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (scoreStore.rename(target.name, newName)) {
                        savedScores = scoreStore.list()
                        if (scoreName == target.name) {
                            scoreName = newName
                            OverlayController.updateScoreName(newName)
                        }
                        Toast.makeText(context, "已重命名为「$newName」", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "重命名失败：名称「$newName」已被占用", Toast.LENGTH_LONG).show()
                    }
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
fun GuideCard(
    step: String,
    title: String,
    done: Boolean,
    description: String,
    action: @Composable () -> Unit
) {
    ElevatedCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (done) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$step · $title", style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                                if (!done) action()
            }
        }
    }
}
