package cc.eu.jeanwenzel.Noctreed.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放状态：空闲 / 演奏中 / 已暂停 */
enum class PlayState { IDLE, PLAYING, PAUSED }

/** 悬浮窗与主界面共享的播放状态总线 */
object OverlayController {
    private val _playState = MutableStateFlow(PlayState.IDLE)
    val playState = _playState.asStateFlow()

    private val _score = MutableStateFlow("")
    val score = _score.asStateFlow()

    private val _bpm = MutableStateFlow(90)
    val bpm = _bpm.asStateFlow()

    /** 校准模式状态（用于悬浮窗 UI 切换） */
    private val _calibrating = MutableStateFlow(false)
    val calibrating = _calibrating.asStateFlow()

    private val _calibPrompt = MutableStateFlow("")
    val calibPrompt = _calibPrompt.asStateFlow()

    /** 当前曲目名（用于悬浮窗显示） */
    private val _scoreName = MutableStateFlow("")
    val scoreName = _scoreName.asStateFlow()

    /** 正在演奏的乐句：index 当前序号（从 1 起）、total 总乐句数、text 该乐句的简谱文本；0/0 表示无 */
    data class PhraseInfo(
        val index: Int,
        val total: Int,
        val text: String = "",
        val lyric: String = ""
    )
    private val _phrase = MutableStateFlow(PhraseInfo(0, 0, ""))
    val phrase = _phrase.asStateFlow()

    fun updateScore(text: String) { _score.value = text }
    fun updateBpm(v: Int) { _bpm.value = v.coerceIn(30, 300) }
    fun updateScoreName(name: String) { _scoreName.value = name }

    /** 演奏中由无障碍服务上报当前乐句 */
    fun notifyPhrase(index: Int, total: Int, text: String = "", lyric: String = "") {
        _phrase.value = PhraseInfo(index, total, text, lyric)
    }

    fun start(context: Context) {
        val service = HarmonicaAccessibilityService.instance
        if (service == null) { setState(PlayState.IDLE); return }
        service.startPlaying(_score.value, _bpm.value)
        setState(PlayState.PLAYING)
    }

    fun pause() {
        val service = HarmonicaAccessibilityService.instance
        if (service == null) { setState(PlayState.IDLE); return }
        service.pausePlaying()
        setState(PlayState.PAUSED)
    }

    fun resume() {
        val service = HarmonicaAccessibilityService.instance
        if (service == null) { setState(PlayState.IDLE); return }
        service.resumePlaying()
        setState(PlayState.PLAYING)
    }

    fun stop() {
        HarmonicaAccessibilityService.instance?.stopPlaying()
        setState(PlayState.IDLE)
        _phrase.value = PhraseInfo(0, 0, "")
    }

    fun notifyPlayState(playing: Boolean) {
        if (!playing) {
            setState(PlayState.IDLE)
            _phrase.value = PhraseInfo(0, 0, "")
        }
    }

    fun setCalibrating(v: Boolean, prompt: String = "") {
        _calibrating.value = v
        _calibPrompt.value = prompt
    }

    private fun setState(s: PlayState) {
        _playState.value = s
    }
}