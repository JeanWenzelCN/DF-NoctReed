package cc.eu.jeanwenzel.Noctreed.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.setMargins
import androidx.core.view.setPadding
import cc.eu.jeanwenzel.Noctreed.MainActivity
import cc.eu.jeanwenzel.Noctreed.R
import cc.eu.jeanwenzel.Noctreed.data.CalibrationStore
import cc.eu.jeanwenzel.Noctreed.data.Keys
import cc.eu.jeanwenzel.Noctreed.data.ScoreStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var calibrating = false
    private var calibIndex = 0
    private var collapsed = false
    private lateinit var store: CalibrationStore
    private lateinit var scoreStore: ScoreStore
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var scoreNameView: TextView
    private lateinit var phraseView: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPause: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var btnCalibrate: ImageButton
    private lateinit var btnMinimize: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnLibrary: ImageButton
    private lateinit var btnBpmDown: ImageButton
    private lateinit var btnBpmUp: ImageButton
    private lateinit var bpmView: TextView
    private lateinit var expandedContent: LinearLayout
    private var isPortrait = true
    private var header: LinearLayout? = null
    private var pendingCalibrate = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        store = CalibrationStore(this)
        scoreStore = ScoreStore(this)
        startForeground(1, buildNotification())
        showPanel()
        // 订阅状态总线：状态变化自动刷新悬浮窗（修复按钮偶发失效）
        serviceScope.launch { OverlayController.playState.collect { refreshPanel() } }
        serviceScope.launch { OverlayController.scoreName.collect { refreshPanel() } }
        serviceScope.launch { OverlayController.phrase.collect { refreshPanel() } }
            serviceScope.launch { OverlayController.bpm.collect { refreshPanel() } }
        // 兜底：悬浮窗创建后用户可能才去开无障碍，轮询刷新门控直到拿到为止
        serviceScope.launch {
            while (!HarmonicaAccessibilityService.isEnabled()) {
                kotlinx.coroutines.delay(1500)
                refreshPanel()
            }
            refreshPanel() // 刚拿到权限时立即刷新一次
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowPortrait = newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (nowPortrait != isPortrait) {
            isPortrait = nowPortrait
            // 旋转时停止演奏，避免横竖屏切换后状态错乱
            if (OverlayController.playState.value != PlayState.IDLE) OverlayController.stop()
            // 旋转时彻底重建悬浮窗，确保横竖屏布局真正分开
            panel?.let { wm.removeView(it) }
            panel = null
            showPanel()
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NoctReed 正在运行")
            .setContentText("点击返回应用")
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(pi)
            .build()
    }

    private fun lp() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 40
        y = 300
    }

    /** 可复用的圆角按钮构造 */
    private fun makeButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = desc
            background = GradientDrawable().apply {
                cornerRadius = 20f * resources.displayMetrics.density
                setColor(BTN_BG)
            }
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF1F1F1F.toInt())
            val size = (36 * resources.displayMetrics.density).toInt()
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = (6 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(5.dpToPx(), 0, 5.dpToPx(), 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showPanel() {
        val params = lp()

        // 根容器：圆角卡片样式
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(PANEL_BG)
            }
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 10.dpToPx())
        }

        // 标题栏：NoctReed + 状态文本 + 最小化/关闭按钮（单行）
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title = TextView(this).apply {
            text = "NoctReed"
            setTextColor(TEXT_PRIMARY)
            textSize = 14f
        }
        status = TextView(this).apply {
            text = "就绪"
            setTextColor(TEXT_SECONDARY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
            maxWidth = 110.dpToPx()
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnMinimize = makeButton(R.drawable.ic_minimize, "最小化") { toggleCollapse() }
        btnClose = makeButton(R.drawable.ic_close, "关闭悬浮窗") {
            // 退出悬浮窗前先停止演奏，避免后台继续跑
            if (OverlayController.playState.value != PlayState.IDLE) OverlayController.stop()
            OverlayService.stop(this)
        }
        btnClose.background = GradientDrawable().apply {
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(DANGER_BG)
        }
        btnClose.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        header.addView(title)
        header.addView(status)
        header.addView(btnMinimize)
        header.addView(btnClose)
        root.addView(header)

        // 展开内容：横屏与竖屏分别设计
        // 横屏：所有信息单行显示（约 1/4 屏高，高度不随乐句变化）
        // 竖屏：信息 → BPM 行 → 控制行三行堆叠，宽度远小于屏宽不溢出
        isPortrait = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        expandedContent = LinearLayout(this).apply {
            orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6.dpToPx(), 0, 0)
        }
        val bpmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左侧信息列：曲名 + 乐句（演奏时可见）
        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 8.dpToPx(), 0)
        }
        scoreNameView = TextView(this).apply {
            setTextColor(ACCENT)
            textSize = 13f
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        phraseView = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            textSize = 12f
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoColumn.addView(scoreNameView)
        infoColumn.addView(phraseView)
        if (isPortrait) expandedContent.addView(infoColumn)
        // BPM 实时调速：减号 / 数值 / 加号
        btnBpmDown = makeButton(R.drawable.ic_remove, "减速") {
            OverlayController.updateBpm(OverlayController.bpm.value - 5)
        }
        bpmView = TextView(this).apply {
            setTextColor(TEXT_PRIMARY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(2.dpToPx(), 0, 2.dpToPx(), 0)
        }
        btnBpmUp = makeButton(R.drawable.ic_add, "加速") {
            OverlayController.updateBpm(OverlayController.bpm.value + 5)
        }
        bpmRow.addView(btnBpmDown)
        bpmRow.addView(bpmView)
        bpmRow.addView(btnBpmUp)
        // 播放控制按钮：开始 / 暂停 / 停止 / 乐谱库 / 校准
        btnPlay = makeButton(R.drawable.ic_play, "开始演奏") { OverlayController.start(this) }
        btnPause = makeButton(R.drawable.ic_pause, "暂停 / 恢复") {
            when (OverlayController.playState.value) {
                PlayState.PLAYING -> OverlayController.pause()
                PlayState.PAUSED -> OverlayController.resume()
                else -> {}
            }
        }
        btnStop = makeButton(R.drawable.ic_stop, "停止演奏") { OverlayController.stop() }
        btnLibrary = makeButton(R.drawable.ic_library, "切换乐谱") { showScorePicker() }
        btnCalibrate = makeButton(R.drawable.ic_tune, "校准按键") {
            if (isPortrait) {
                android.widget.Toast.makeText(this, "竖屏不可校准，请切换到横屏", android.widget.Toast.LENGTH_SHORT).show()
                return@makeButton
            }
            if (!calibrating && !pendingCalibrate && store.allCalibrated()) {
                android.widget.Toast.makeText(this, "已完成校准，如需重新校准请在主界面清除后重试", android.widget.Toast.LENGTH_SHORT).show()
                return@makeButton
            }
            if (calibrating) {
                // 校准中点击 = 取消校准
                calibrating = false
                pendingCalibrate = false
                refreshPanel()
            } else if (pendingCalibrate) {
                // 待确认态点击 = 确认，开始校准
                pendingCalibrate = false
                startCalibration()
            } else {
                // 第一次点击：进入待确认态，5 秒内未确认自动取消
                pendingCalibrate = true
                refreshPanel()
                serviceScope.launch {
                    delay(5000)
                    if (pendingCalibrate && !calibrating) {
                        pendingCalibrate = false
                        refreshPanel()
                    }
                }
            }
        }
        // 待确认态长按 = 取消（防误触后可退出）
        btnCalibrate.setOnLongClickListener {
            if (pendingCalibrate && !calibrating) {
                pendingCalibrate = false
                refreshPanel()
                true
            } else false
        }
        ctrlRow.addView(btnPlay)
        ctrlRow.addView(btnPause)
        ctrlRow.addView(btnStop)
        ctrlRow.addView(btnLibrary)
        ctrlRow.addView(btnCalibrate)
        if (isPortrait) {
            expandedContent.addView(bpmRow)
            expandedContent.addView(ctrlRow)
        } else {
            // 横屏：所有信息单行平铺，高度固定 ≈ 1/4 屏高
            expandedContent.addView(infoColumn)
            expandedContent.addView(bpmRow)
            expandedContent.addView(ctrlRow)
        }
        root.addView(expandedContent)

        // 拖动支持
        var downX = 0f; var downY = 0f; var moved = false
        root.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) {
                        moved = true
                        params.x += dx.toInt(); params.y += dy.toInt()
                        downX = ev.rawX; downY = ev.rawY
                        wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { true }
                else -> false
            }
        }

        panel = root
        wm.addView(root, params)
        refreshPanel()
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        expandedContent.visibility = if (collapsed) View.GONE else View.VISIBLE
        btnMinimize.setImageResource(if (collapsed) R.drawable.ic_expand else R.drawable.ic_minimize)
        btnMinimize.contentDescription = if (collapsed) "展开" else "最小化"
    }

    /** 悬浮窗内切换乐谱：弹出已保存乐谱列表，选中后同步三件套 */
    private fun showScorePicker() {
        val scores = scoreStore.list()
        if (scores.isEmpty()) {
            android.widget.Toast.makeText(this, "乐谱库为空，请先在应用内保存乐谱", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val names = scores.map { "${it.name}（${it.bpm} BPM）" }.toTypedArray()
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("切换乐谱")
            .setItems(names) { d, which ->
                val s = scores[which]
                OverlayController.updateScore(s.text)
                OverlayController.updateBpm(s.bpm)
                OverlayController.updateScoreName(s.name)
                refreshPanel()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .create()
        // 悬浮窗服务里弹窗必须用 overlay window 类型，否则 BadTokenException
        dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dlg.show()
    }

    /** 刷新悬浮窗显示（供外部调用） */
    fun refreshPanel() {
        if (!::title.isInitialized) return // 面板尚未创建时忽略状态推送
        val calibKey = if (calibrating) Keys.ALL.getOrNull(calibIndex) else null
        when {
            calibrating && calibKey != null -> {
                status.text = "校准中：请点击「$calibKey」(${calibIndex + 1}/${Keys.ALL.size})"
                btnCalibrate.setColorFilter(ACCENT)
            }
            calibrating && calibKey == null -> {
                status.text = "校准完成，共 ${Keys.ALL.size} 个键位"
                btnCalibrate.clearColorFilter()
            }
            pendingCalibrate -> {
                status.text = "再点一次开始，长按取消"
                btnCalibrate.setColorFilter(ACCENT)
            }
            else -> {
                val accOn = HarmonicaAccessibilityService.isEnabled()
                status.text = when {
                    !accOn -> "缺少无障碍权限"
                    isPortrait -> "请切换到横屏"
                    else -> when (OverlayController.playState.value) {
                        PlayState.PLAYING -> "演奏中…"
                        PlayState.PAUSED -> "已暂停"
                        PlayState.IDLE -> if (store.allCalibrated()) "就绪（音量键可暂停/停止）" else "未校准"
                    }
                }
                btnCalibrate.clearColorFilter()
            }
        }
        // 已校准且横屏时禁用校准按钮（此时无需再校准，防止误触清空流程）
        btnCalibrate.isEnabled = !isPortrait && !store.allCalibrated()
        btnCalibrate.alpha = if (btnCalibrate.isEnabled) 1f else 0.4f
        // 演奏/暂停按钮可用性：用 isEnabled 真实禁用（修复视觉灰但仍可点的缺陷）
        val st = OverlayController.playState.value
        val playing = st == PlayState.PLAYING
        val paused = st == PlayState.PAUSED
        btnPlay.isEnabled = HarmonicaAccessibilityService.isEnabled() && !isPortrait && !playing && !paused
        btnPlay.alpha = if (btnPlay.isEnabled) 1f else 0.4f
        btnPause.isEnabled = playing || paused
        btnPause.alpha = if (btnPause.isEnabled) 1f else 0.4f
        btnStop.isEnabled = playing || paused
        btnStop.alpha = if (btnStop.isEnabled) 1f else 0.4f

        // 曲目名与乐句：仅演奏/暂停时可见
        val name = OverlayController.scoreName.value
        val phrase = OverlayController.phrase.value
        if ((playing || paused) && name.isNotEmpty()) {
            scoreNameView.visibility = View.VISIBLE
            scoreNameView.text = name
        } else {
            scoreNameView.visibility = View.GONE
        }
        if ((playing || paused) && phrase.total > 0) {
            phraseView.visibility = View.VISIBLE
            // 有歌词时优先显示歌词（替换乐句简谱文本，不新增行、不改变悬浮窗尺寸）
            phraseView.text = if (phrase.lyric.isNotEmpty())
                "第 ${phrase.index}/${phrase.total} 句  ${phrase.lyric}"
            else if (phrase.text.isNotEmpty())
                "第 ${phrase.index}/${phrase.total} 句  ${phrase.text}"
            else
                "第 ${phrase.index} / ${phrase.total} 句"
        } else {
            phraseView.visibility = View.GONE
        }

        // 同步最小化/展开图标，防止状态漂移
        btnMinimize.setImageResource(if (collapsed) R.drawable.ic_expand else R.drawable.ic_minimize)

        // BPM 实时显示
        bpmView.text = "${OverlayController.bpm.value} BPM"
    }

    /** 启动校准模式：全屏透明层捕获点击，悬浮窗同步提示 */
    fun startCalibration() {
        if (calibrating) return
        calibrating = true
        calibIndex = 0
        refreshPanel()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val layer = View(this)
        layer.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                val key = Keys.ALL.getOrNull(calibIndex)
                if (key != null) {
                    store.save(key, ev.rawX, ev.rawY)
                    calibIndex++
                    refreshPanel()
                    if (calibIndex >= Keys.ALL.size) {
                        calibrating = false
                        wm.removeView(layer)
                        refreshPanel()
                    }
                }
                true
            } else false
        }
        wm.addView(layer, params)
    }

    override fun onDestroy() {
        panel?.let { wm.removeView(it) }
        panel = null
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        if (intent?.action == ACTION_REFRESH) refreshPanel()
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val ACTION_REFRESH = "cc.eu.jeanwenzel.Noctreed.action.REFRESH"

        // 配色：浅色主题，与 Material You 浅色系协调
        private const val PANEL_BG = 0xF0FFFFFF.toInt()
        private const val BTN_BG = 0xFFE8EAED.toInt()
        private const val TEXT_PRIMARY = 0xFF1F1F1F.toInt()
        private const val TEXT_SECONDARY = 0xFF5F6368.toInt()
        private const val ACCENT = 0xFF1A73E8.toInt()
        private const val DANGER_BG = 0xFFE81123.toInt()

        var instance: OverlayService? = null
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        fun refresh(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java).setAction(ACTION_REFRESH)
            ctx.startForegroundService(i)
        }
    }
}