package cc.eu.jeanwenzel.Noctreed.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import cc.eu.jeanwenzel.Noctreed.data.CalibrationStore
import cc.eu.jeanwenzel.Noctreed.data.Keys
import cc.eu.jeanwenzel.Noctreed.score.Register
import cc.eu.jeanwenzel.Noctreed.score.ScoreEvent
import cc.eu.jeanwenzel.Noctreed.score.ScoreParser
import kotlin.coroutines.resume

class HarmonicaAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var paused = false
    private val pauseLock = Object()

    override fun onServiceConnected() {
        instance = this
        store = CalibrationStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopPlaying()
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    /** 单次手势同时按下：taps 为短触的切换键（音区/半音），holdPos 为长按的音符键。
     *  所有 stroke 均从 t=0 开始，同帧下发，彻底消除切换与音符之间的串行延迟。 */
    private suspend fun tapCombo(taps: List<Pair<Float, Float>>, holdPos: Pair<Float, Float>, holdMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val b = GestureDescription.Builder()
        for (pt in taps) {
            val path = Path().apply { moveTo(pt.first, pt.second) }
            b.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        }
        val hp = Path().apply { moveTo(holdPos.first, holdPos.second) }
        b.addStroke(GestureDescription.StrokeDescription(hp, 0, holdMs))
        dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { cont.resume(false) }
        }, null)
    }

    /** 开始演奏 */
    fun startPlaying(scoreText: String, @Suppress("UNUSED_PARAMETER") bpm: Int) {
        stopPlaying()
        val parsed = ScoreParser.parseWithPhrases(scoreText)
        val events = parsed.events
        if (events.isEmpty()) return
        val phraseCount = parsed.phraseCount
        val phrases = parsed.phrases
        val lyrics = parsed.lyrics
        OverlayController.notifyPhrase(
            if (phraseCount > 0) 1 else 0, phraseCount,
            phrases.getOrNull(0) ?: "", lyrics.getOrNull(0) ?: ""
        )
        // 每次取当前 BPM 计算一拍（四分音符）的毫秒数，悬浮窗调速与 bpm= 指令可实时生效
        fun beatMsNow(): Long = (60000L / OverlayController.bpm.value).coerceAtLeast(100L)
        // 事件时值统一为 tick（1 拍 = 16 tick），换算成毫秒
        fun tickMsNow(ticks: Int): Long = beatMsNow() * ticks / ScoreParser.TICKS_PER_BEAT
        paused = false

        playJob = scope.launch {
            var register = Register.NATURAL
            var halfStep = false
            var lastPhrase = -1

            for (ei in events.indices) {
                val event = events[ei]
                awaitIfPaused()
                // 乐句变化时上报悬浮窗（含歌词）
                val pi = event.phraseIndex
                if (pi != lastPhrase) {
                    lastPhrase = pi
                    OverlayController.notifyPhrase(
                        pi + 1, phraseCount,
                        phrases.getOrNull(pi) ?: "", lyrics.getOrNull(pi) ?: ""
                    )
                }
                when (event) {
                    is ScoreEvent.Bpm -> {
                        // 变速指令：立即生效，不产生任何手势与延时
                        OverlayController.updateBpm(event.bpm)
                    }
                    is ScoreEvent.Rest -> delayInterruptibly(tickMsNow(event.ticks))
                    is ScoreEvent.Note -> {
                        // 切换键（音区/半音）与音符键合并进同一次 dispatchGesture，
                        // 所有 stroke 从 t=0 同时按下，切换不再产生任何串行延迟与时值侵占
                        val switches = mutableListOf<Pair<Float, Float>>()
                        if (event.register != register) {
                            val key = when (event.register) {
                                Register.NATURAL -> Keys.NATURAL
                                Register.SHARP -> Keys.SHARP
                                Register.FLAT -> Keys.FLAT
                            }
                            store.get(key)?.let { switches.add(it) }
                            register = event.register
                        }
                        if (event.halfStep != halfStep) {
                            store.get(Keys.HALF_STEP)?.let { switches.add(it) }
                            halfStep = event.halfStep
                        }
                        // 音符键：长按，按住时长约为音长的 90%（长音保持按下，短音近似点触）
                        val noteKey = if (event.degree == 8) "i" else event.degree.toString()
                        val noteMs = tickMsNow(event.ticks)
                        // 极短音符（如高 BPM 下的十六分音符）不再追求 90% 按住比例，
                        // 优先保证按下与松开之间留有间隙，降低糊音/吞音概率
                        val holdMs = if (noteMs >= 200L) {
                            (noteMs * 9 / 10).coerceAtLeast(60L)
                        } else {
                            (noteMs - 30L).coerceIn(30L, 60L)
                        }
                        val notePos = store.get(noteKey)
                        if (notePos != null) {
                            tapCombo(switches, notePos, holdMs)
                        }
                        delayInterruptibly(noteMs - holdMs)
                    }
                }
            }
            OverlayController.notifyPlayState(false)
        }
    }

    /** 暂停时挂起等待，恢复后继续 */
    private suspend fun awaitIfPaused() {
        while (paused) {
            kotlinx.coroutines.delay(100)
        }
    }

    /** 可被暂停感知的 delay：分段等待，暂停时暂停计时 */
    private suspend fun delayInterruptibly(ms: Long) {
        var remaining = ms
        val step = 50L
        while (remaining > 0) {
            awaitIfPaused()
            val d = minOf(step, remaining)
            kotlinx.coroutines.delay(d)
            remaining -= d
        }
    }

    fun pausePlaying() {
        if (playJob?.isActive == true) paused = true
    }

    fun resumePlaying() {
        paused = false
    }

    fun stopPlaying() {
        playJob?.cancel()
        playJob = null
        paused = false
    }

    fun isPlaying() = playJob?.isActive == true && !paused

    companion object {
        var instance: HarmonicaAccessibilityService? = null
            private set
        lateinit var store: CalibrationStore
            private set

        fun isEnabled() = instance != null
    }
}