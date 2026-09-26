package cc.eu.jeanwenzel.Noctreed.score

/** 音区状态（互斥） */
enum class Register { NATURAL, SHARP, FLAT }

/**
 * 时值以 tick 计量：1 拍（四分音符）= 16 tick。
 * 八分音符 = 8 tick，十六分音符 = 4 tick；附点 = 时值 × 3/2。
 */
sealed class ScoreEvent {
    abstract val ticks: Int
    abstract val phraseIndex: Int

    /** 音符：degree 1..8（8 = 高音 i） */
    data class Note(
        val degree: Int,
        val register: Register,
        val halfStep: Boolean,
        override val ticks: Int,
        override val phraseIndex: Int = 0
    ) : ScoreEvent()

    data class Rest(override val ticks: Int, override val phraseIndex: Int = 0) : ScoreEvent()

    /** bpm=x 指令：演奏到此处时实时切换 BPM */
    data class Bpm(val bpm: Int, override val phraseIndex: Int = 0) : ScoreEvent() {
        override val ticks: Int = 0
    }
}

/**
 * 标准数字简谱（jianpu）解析器。
 * 记法约定：
 *  - 基本音级：1 2 3 4 5 6 7，8/i 也表示高音 do
 *  - 高音点：数字后加点，如 1.（游戏只有一档高音区）
 *  - 低音点：数字前加点，如 .1
 *  - 半音：#4 / ♯4 升半音，b7 / ♭7 / B7 降半音（等效降调后升半音）
 *  - 时值：四分音符 = 数字本体（1 拍）；`1_` 或 `1/` = 八分音符（1/2 拍）；
 *    `1__` 或 `1//` = 十六分音符（1/4 拍）；附点 `1_.` / `1/.` / `1__.` / `1//.` = 时值 × 3/2；
 *    音符后每多一个 `-` 延长一拍，如 5--（共 3 拍）；单独的 `-` 延长上一音符一拍
 *  - 休止：0，后随 `-` 延长，如 0-（共 2 拍）；也支持 0_ / 0__ 等分时值
 *  - 歌词注释：行内 `$` 之后到行尾的全部内容为歌词，不产生音符，
 *    歌词与其所在行的乐句一一对应，随乐句在悬浮窗显示
 *  - 变速指令：任意位置的独立 token `bpm=x`（如 bpm=120），演奏到该处实时切换 BPM
 *  - 小节线 | 与逗号、换行均视为乐句/拍分隔，自动忽略
 */
object ScoreParser {

    /** 1 拍（四分音符）对应的 tick 数 */
    const val TICKS_PER_BEAT = 16

    data class Parsed(
        val events: List<ScoreEvent>,
        val phraseCount: Int,
        /** 每个乐句的简谱原文（按解析出的有效乐句切分），供悬浮窗实时显示 */
        val phrases: List<String> = emptyList(),
        /** 每个乐句对应的歌词（无歌词的乐句为空串），与 phrases 按下标对齐 */
        val lyrics: List<String> = emptyList()
    )

    fun parse(text: String): List<ScoreEvent> = parseWithPhrases(text).events

    /** 解析并保留乐句边界：按 | 与换行切分乐句；行内 $ 之后为歌词注释 */
    fun parseWithPhrases(text: String): Parsed {
        val events = mutableListOf<ScoreEvent>()
        var phrase = 0
        var sawTokenInPhrase = false
        val phrases = mutableListOf<String>()
        val lyrics = mutableListOf<String>()
        val phraseTokens = StringBuilder()

        fun flushPhrase(lyric: String) {
            if (phrases.size <= phrase) {
                phrases.add(phraseTokens.toString().trim())
                lyrics.add(lyric.trim())
            }
        }

        // 逐行处理：先剥离 $ 歌词注释，再扫描音符 token
        val lines = text.split('\n')
        for (rawLine in lines) {
            val dollar = rawLine.indexOf('$')
            val lyric = if (dollar >= 0) rawLine.substring(dollar + 1) else ""
            val music = if (dollar >= 0) rawLine.substring(0, dollar) else rawLine
            val lineStartPhrase = phrase

            // 统一分隔符：逗号 → 空白；| 与回车是乐句边界
            val normalized = music.replace('，', ' ').replace(',', ' ')
            val tokens = mutableListOf<Pair<String, Boolean>>() // token -> 是否为乐句边界
            val sb = StringBuilder()
            fun flushToken() {
                if (sb.isNotEmpty()) { tokens.add(sb.toString() to false); sb.clear() }
            }
            for (c in normalized) {
                when {
                    c == '|' || c == '\r' -> { flushToken(); tokens.add("" to true) }
                    c.isWhitespace() -> flushToken()
                    else -> sb.append(c)
                }
            }
            flushToken()

            for ((token, boundary) in tokens) {
                if (boundary) {
                    if (sawTokenInPhrase) {
                        // 歌词归属于其所在行开始的那个乐句
                        flushPhrase(if (phrase == lineStartPhrase) lyric else "")
                        phraseTokens.clear()
                        phrase++
                        sawTokenInPhrase = false
                    }
                    continue
                }
                if (phraseTokens.isNotEmpty()) phraseTokens.append(' ')
                phraseTokens.append(token)
                val before = events.size
                parseToken(token, events, phrase)
                if (events.size > before) sawTokenInPhrase = true
            }

            // 行尾：结束当前乐句，歌词归入本行第一个乐句
            if (sawTokenInPhrase) {
                flushPhrase(if (phrase == lineStartPhrase) lyric else "")
                phraseTokens.clear()
                phrase++
                sawTokenInPhrase = false
            }
        }
        return Parsed(events, if (events.isEmpty()) 0 else phrase, lyrics)
    }

    private val BPM_REGEX = Regex("(?i)^bpm=(\\d{2,3})$")

    private fun parseToken(t: String, events: MutableList<ScoreEvent>, phrase: Int) {
        val bpmMatch = BPM_REGEX.matchEntire(t)
        if (bpmMatch != null) {
            val v = bpmMatch.groupValues[1].toIntOrNull() ?: return
            events.add(ScoreEvent.Bpm(v, phrase))
            return
        }
        if (t == "-") { extendLast(events, TICKS_PER_BEAT, phrase); return }
        if (t.all { it == '0' || it == '-' } && t.contains('0')) {
            val ticks = TICKS_PER_BEAT * (1 + t.count { it == '-' })
            events.add(ScoreEvent.Rest(ticks, phrase))
            return
        }
        parseNoteToken(t, events, phrase)
    }

    private fun parseNoteToken(token: String, events: MutableList<ScoreEvent>, phrase: Int) {
        var s = token
        var halfStep = false
        var register = Register.NATURAL

        // 前置升降记号
        while (s.startsWith("#") || s.startsWith("♯")) { halfStep = true; s = s.drop(1) }
        while (s.startsWith("b") || s.startsWith("B") || s.startsWith("♭")) { halfStep = true; s = s.drop(1) }
        // 前置低音点
        while (s.startsWith(".")) { register = Register.FLAT; s = s.drop(1) }

        // 后置延音线（每个 - 延长一拍）
        var extraBeats = 0
        while (s.endsWith("-")) { extraBeats++; s = s.dropLast(1) }

        // 后置附点：仅当 . 前面是 _ 或 / 时视为附点（否则是高音点）
        var dotted = false
        if (s.length >= 2 && s.endsWith(".") && (s[s.length - 2] == '_' || s[s.length - 2] == '/')) {
            dotted = true
            s = s.dropLast(1)
        }

        // 后置分时值记号：_ 或 /，1 个 = 八分，2 个 = 十六分
        var divisor = 1
        run {
            var marks = 0
            while (s.endsWith("_") || s.endsWith("/")) { marks++; s = s.dropLast(1) }
            if (marks > 0) divisor = if (marks >= 2) 4 else 2
        }

        // 后置高音点
        var highDots = 0
        while (s.endsWith(".")) { highDots++; s = s.dropLast(1) }
        if (highDots > 0) register = Register.SHARP

        if (s.isEmpty()) return
        val degree = when (s) {
            "i", "I", "8" -> 8
            else -> s.toIntOrNull()
        } ?: return // 无法识别，静默跳过
        if (degree !in 0..8) return

        var ticks = TICKS_PER_BEAT / divisor + extraBeats * TICKS_PER_BEAT
        if (dotted) ticks = ticks * 3 / 2

        if (degree == 0) {
            events.add(ScoreEvent.Rest(ticks, phrase))
        } else {
            events.add(ScoreEvent.Note(degree, register, halfStep, ticks, phrase))
        }
    }

    private fun extendLast(events: MutableList<ScoreEvent>, extraTicks: Int, phrase: Int) {
        when (val last = events.lastOrNull()) {
            null -> events.add(ScoreEvent.Rest(extraTicks, phrase))
            is ScoreEvent.Note -> events[events.lastIndex] = last.copy(ticks = last.ticks + extraTicks)
            is ScoreEvent.Rest -> events[events.lastIndex] = last.copy(ticks = last.ticks + extraTicks)
            // 变速指令不可延长，独立的 `-` 视为一个休止
            is ScoreEvent.Bpm -> events.add(ScoreEvent.Rest(extraTicks, phrase))
        }
    }

    const val SAMPLE = "1 1 5_ 5_ | 6 6 5- | 4 4 3_ 3_ | 2 2 1-"
}
