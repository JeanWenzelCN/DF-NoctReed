package cc.eu.jeanwenzel.Noctreed.score

import java.io.InputStream

/** MIDI 导入异常，message 为可直接展示给用户的中文提示 */
class MidiException(message: String) : Exception(message)

/**
 * 轻量标准 MIDI 文件（SMF）导入器。
 * 仅支持单旋律（单音轨、无和弦）MIDI，并将其转换为游戏口琴可演奏的标准简谱文本。
 * 校验规则：单音轨 / 无和弦（无同时发声）/ 音域必须落在可吹范围内。
 */
object MidiImporter {

    data class Result(val jianpu: String, val bpm: Int, val noteCount: Int)

    /** do=C5(72) 时可吹的自然音：低音 .1-.7（C4-B4）、中音 1-7、i（C6）、高音 1.-7.（含 i. = C7） */
    private val BASE = intArrayOf(60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79, 81, 83, 84, 86, 88, 89, 91, 93, 95, 96)
    private val BASE_SET = BASE.toSet()
    /** 可吹音集合 = 自然音 + 每个自然音的升半音 */
    private val PLAYABLE: Set<Int> = buildSet {
        for (b in BASE) { add(b); add(b + 1) }
    }

    fun parse(input: InputStream): Result {
        val data = input.readBytes()
        var pos = 0

        fun need(n: Int) {
            if (pos + n > data.size) throw MidiException("MIDI 文件已损坏（数据长度不足）")
        }
        fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = ((u8().toLong() shl 24) or (u8().toLong() shl 16) or
                (u8().toLong() shl 8) or u8().toLong()) and 0xFFFFFFFFL
        fun str(n: Int): String { need(n); val s = String(data, pos, n); pos += n; return s }
        fun varLen(): Long {
            var value = 0L
            var count = 0
            while (true) {
                val b = u8()
                value = (value shl 7) or (b and 0x7F).toLong()
                count++
                if (count > 4) throw MidiException("MIDI 文件已损坏（变长量过长）")
                if (b and 0x80 == 0) break
            }
            return value
        }

        if (data.size < 14 || str(4) != "MThd") throw MidiException("不是有效的 MIDI 文件（缺少 MThd 头）")
        val headerLen = u32()
        if (headerLen < 6) throw MidiException("MIDI 文件已损坏（头部长度异常）")
        val format = u16()
        val trackCount = u16()
        val division = u16()
        if (format == 2) throw MidiException("不支持 format 2 的 MIDI 文件，请使用单音轨 MIDI")
        if (division and 0x8000 != 0) throw MidiException("不支持 SMPTE 时间格式的 MIDI 文件")
        val ppq = division and 0x7FFF
        if (ppq <= 0) throw MidiException("MIDI 文件已损坏（PPQ 异常）")
        // 跳过头部多余字节
        pos = (8 + headerLen.toInt()).coerceAtMost(data.size)

        data class NoteOn(val tick: Long, val pitch: Int)
        data class TrackNotes(val notes: MutableList<NoteOn>, var tempoUs: Long)

        var tempoUs: Long = 500000 // 默认 120 BPM
        val voicedTracks = mutableListOf<TrackNotes>()

        for (t in 0 until trackCount) {
            if (pos + 8 > data.size) break
            if (str(4) != "MTrk") throw MidiException("MIDI 文件已损坏（缺少 MTrk 块）")
            val trackLen = u32().toInt()
            val end = (pos + trackLen).coerceAtMost(data.size)

            var tick = 0L
            var status = 0
            var activePitch = -1
            val notes = mutableListOf<NoteOn>()
            var hasNote = false

            while (pos < end) {
                tick += varLen()
                var b = u8()
                if (b < 0x80) {
                    // running status：回退一字节
                    pos--
                    b = status
                } else {
                    status = b
                }
                when (b and 0xF0) {
                    0x80 -> { val pitch = u8(); u8(); if (pitch == activePitch) activePitch = -1 }
                    0x90 -> {
                        val pitch = u8(); val vel = u8()
                        if (vel > 0) {
                            if (activePitch >= 0) throw MidiException(
                                "检测到和弦/复音：同一时刻存在多个音符。游戏口琴一次只能吹一个音，请使用单旋律（无和弦）MIDI")
                            activePitch = pitch
                            notes.add(NoteOn(tick, pitch))
                            hasNote = true
                        } else if (pitch == activePitch) activePitch = -1
                    }
                    0xA0, 0xB0, 0xE0 -> { u8(); u8() }
                    0xC0, 0xD0 -> { u8() }
                    0xF0 -> when (b) {
                        0xFF -> {
                            val meta = u8()
                            val len = varLen().toInt()
                            if (meta == 0x51 && len == 3) {
                                tempoUs = ((u8() shl 16) or (u8() shl 8) or u8()).toLong()
                            } else {
                                need(len); pos += len
                            }
                            if (meta == 0x2F) { pos = end; break }
                        }
                        0xF0, 0xF7 -> { val len = varLen().toInt(); need(len); pos += len }
                        else -> { /* 忽略其他系统事件 */ }
                    }
                }
            }
            pos = end
            if (hasNote) voicedTracks.add(TrackNotes(notes, tempoUs))
        }

        if (voicedTracks.size > 1) throw MidiException(
            "检测到 ${voicedTracks.size} 个发声轨道：游戏口琴一次只能吹一个音，请使用单音轨 MIDI 文件")
        val track = voicedTracks.firstOrNull() ?: throw MidiException("未解析到任何音符，MIDI 文件为空")

        // 音域校验
        val outOfRange = track.notes.map { it.pitch }.distinct().filter { it !in PLAYABLE }
        if (outOfRange.isNotEmpty()) throw MidiException(
            "音域超出游戏口琴可吹范围（存在 MIDI 音高 ${outOfRange.first()}）。请移调至低音 5 ～ 高音 1（C4 为 do）之间，否则无法吹奏")

        val bpm = (60000000L / track.tempoUs).toInt().coerceIn(30, 300)

        // 量化与转简谱。时值单位统一为 tick（1 拍四分音符 = 16 tick），可表达八分/十六分
        val events = mutableListOf<Pair<String, Int>>() // token to ticks(>=4)
        var prevTick: Long? = null
        val minGap = (ppq / 8).coerceAtLeast(1) // 小于 1/32 拍的间隙忽略
        for ((i, n) in track.notes.withIndex()) {
            val start = n.tick
            val end = track.notes.getOrNull(i + 1)?.tick ?: (start + ppq)
            val durTicks = (end - start).coerceAtLeast(1)
            // 量化到十六分音符（tick 粒度 4，四舍五入），至少一个十六分
            val ticks = ((Math.round(durTicks.toDouble() * 16.0 / ppq).toInt() + 2) / 4 * 4).coerceAtLeast(4)
            // 与前一个音之间的休止
            val p = prevTick
            if (p != null && start - p > minGap) {
                val restTicks = ((Math.round((start - p).toDouble() * 16.0 / ppq).toInt() + 2) / 4 * 4).coerceAtLeast(4)
                events.add("0" to restTicks)
            }
            events.add(toJianpuToken(n.pitch) to ticks)
            prevTick = start + durTicks
        }

        val sb = StringBuilder()
        events.forEachIndexed { i, (token, ticks) ->
            if (i > 0) {
                sb.append(' ')
                if (i % 8 == 0) sb.append("| ")
            }
            sb.append(token)
            // tick → 记法：整拍部分用 - 延音，零头 8 tick 加一个 _，4 tick 加 __
            val wholeBeats = ticks / ScoreParser.TICKS_PER_BEAT
            val rem = ticks % ScoreParser.TICKS_PER_BEAT
            repeat((wholeBeats - 1).coerceAtLeast(0)) { sb.append('-') }
            if (rem >= 8) sb.append('_')
            if (rem % 8 >= 4) sb.append("__")
        }
        return Result(sb.toString().trim(), bpm, track.notes.size)
    }

    /** do=C5(72)：60-71→.1-.7，72-83→1-7，84→i（与 1. 等音，优先记作 i），85-95→高音区，96→i.；非自然音视为升半音（前置 #） */
    private fun toJianpuToken(pitch: Int): String {
        val isSharp = pitch !in BASE_SET
        val base = if (isSharp) pitch - 1 else pitch
        val degree = when (base) {
            60 -> ".1"; 62 -> ".2"; 64 -> ".3"; 65 -> ".4"
            67 -> ".5"; 69 -> ".6"; 71 -> ".7"
            72 -> "1"; 74 -> "2"; 76 -> "3"; 77 -> "4"
            79 -> "5"; 81 -> "6"; 83 -> "7"; 84 -> "i"
            86 -> "2."; 88 -> "3."; 89 -> "4."
            91 -> "5."; 93 -> "6."; 95 -> "7."; 96 -> "i."
            else -> throw MidiException("内部错误：无法映射的 MIDI 音高 $pitch")
        }
        return if (isSharp) "#$degree" else degree
    }
}
