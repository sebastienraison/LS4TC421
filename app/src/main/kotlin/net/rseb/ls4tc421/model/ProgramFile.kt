package net.rseb.ls4tc421.model

import ls4tc421.protocol.Limits
import ls4tc421.protocol.ProgramName

/** Why a file was refused. [where] names the faulty field (JSON path, not translated). */
sealed interface ImportError {
    data object TooLarge : ImportError
    data class NotJson(val detail: String) : ImportError
    data object NotAProgram : ImportError
    data class UnsupportedVersion(val version: String) : ImportError
    data class Invalid(val where: String, val detail: String) : ImportError

    /** The name breaks the rule for sending (D23): too long, or a refused character. */
    data class InvalidName(val problem: ProgramName.Problem) : ImportError
}

sealed interface ImportResult {
    data class Ok(val spec: ProgramSpec) : ImportResult
    data class Refused(val error: ImportError) : ImportResult
}

/**
 * The exchange file of a program, documented in docs/PROGRAM-FORMAT.md: JSON, with the key points,
 * the options and the 48 computed steps (so that another tool can read it without recomputing).
 */
object ProgramFile {
    const val FORMAT = "ls4tc421-program"
    const val VERSION = 1L

    /** Files bigger than this are refused before being read (a program file is about 5 KB). */
    const val MAX_BYTES = 256 * 1024

    /** Suggested file name: the program name with unsafe characters replaced. */
    fun fileName(spec: ProgramSpec): String {
        val base = spec.name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().trimStart('.')
        return (base.ifEmpty { "program" }) + ".json"
    }

    fun encode(spec: ProgramSpec): String {
        val clean = spec.pruned()
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"format\": ").append(Json.quote(FORMAT)).append(",\n")
        out.append("  \"version\": ").append(VERSION).append(",\n")
        out.append("  \"name\": ").append(Json.quote(clean.name)).append(",\n")
        out.append("  \"symmetry\": ")
        val axis = clean.symmetryAxis
        out.append(if (axis == null) "null" else "{ \"axis\": ${Json.quote(quarterTime(axis))} }").append(",\n")
        out.append("  \"channels\": [\n")
        for (channel in 0 until CHANNELS) {
            out.append("    {\n")
            out.append("      \"channel\": ").append(channel + 1).append(",\n")
            val link = clean.links[channel]
            out.append("      \"follows\": ")
            out.append(if (link == null) "null" else "{ \"channel\": ${link.source + 1}, \"percent\": ${link.percent} }")
            out.append(",\n")
            out.append("      \"points\": [")
            val points = clean.points[channel]
            points.forEachIndexed { index, point ->
                out.append(if (index == 0) "\n" else ",\n")
                out.append("        { \"time\": ").append(Json.quote(stepTime(point.step)))
                out.append(", \"level\": ").append(point.level).append(" }")
            }
            out.append(if (points.isEmpty()) "]\n" else "\n      ]\n")
            out.append(if (channel < CHANNELS - 1) "    },\n" else "    }\n")
        }
        out.append("  ],\n")
        out.append("  \"steps\": [\n")
        val levels = clean.levels()
        levels.forEachIndexed { step, values ->
            out.append("    { \"time\": ").append(Json.quote(stepTime(step)))
            out.append(", \"levels\": [").append(values.joinToString(", ")).append("] }")
            out.append(if (step < STEPS - 1) ",\n" else "\n")
        }
        out.append("  ]\n")
        out.append("}\n")
        return out.toString()
    }

    /**
     * Reads a program file. The key points and options are read; the steps are ignored when key
     * points are present. A file with only `steps` is imported as 48 key points per channel.
     * Anything invalid refuses the whole file.
     */
    fun decode(text: String): ImportResult {
        if (text.length > MAX_BYTES) return refuse(ImportError.TooLarge)
        val root = try {
            Json.parse(text)
        } catch (e: Json.ParseException) {
            return refuse(ImportError.NotJson(e.message.orEmpty()))
        }
        if (root !is Map<*, *> || root["format"] != FORMAT) return refuse(ImportError.NotAProgram)
        val version = root["version"]
        if (version != VERSION) return refuse(ImportError.UnsupportedVersion(version.toString()))
        return try {
            ImportResult.Ok(read(root))
        } catch (e: BadName) {
            refuse(ImportError.InvalidName(e.problem))
        } catch (e: Bad) {
            refuse(ImportError.Invalid(e.where, e.detail))
        }
    }

    private fun refuse(error: ImportError) = ImportResult.Refused(error)

    private class Bad(val where: String, val detail: String) : Exception("$where: $detail")

    private class BadName(val problem: ProgramName.Problem) : Exception("name: $problem")

    private fun read(root: Map<*, *>): ProgramSpec {
        val name = root["name"] as? String ?: throw Bad("name", "a text is expected")
        // Same rule as at entry (D23): no automatic correction, the file is refused.
        when (val problem = AppName.check(name)) {
            null -> Unit
            ProgramName.Problem.Empty -> throw Bad("name", "1 to ${AppName.MAX_LENGTH} characters expected")
            else -> throw BadName(problem)
        }
        val channels = root["channels"]
        if (channels == null) {
            val steps = readSteps(root["steps"] ?: throw Bad("channels", "neither channels nor steps"))
            return ProgramSpec.fromSteps(name, steps)
        }
        val axis = when (val symmetry = root["symmetry"]) {
            null, false -> null
            is Map<*, *> -> readQuarter(symmetry["axis"], "symmetry.axis")
            else -> throw Bad("symmetry", "an object or null is expected")
        }
        if (channels !is List<*> || channels.size != CHANNELS) throw Bad("channels", "$CHANNELS channels expected")
        val points = arrayOfNulls<List<KeyPoint>>(CHANNELS)
        val links = arrayOfNulls<ChannelLink>(CHANNELS)
        channels.forEachIndexed { index, entry ->
            val where = "channels[$index]"
            if (entry !is Map<*, *>) throw Bad(where, "an object is expected")
            val number = readInt(entry["channel"], "$where.channel", 1..CHANNELS)
            if (points[number - 1] != null) throw Bad("$where.channel", "channel $number appears twice")
            val list = entry["points"] as? List<*> ?: throw Bad("$where.points", "a list is expected")
            val read = list.mapIndexed { i, raw ->
                val at = "$where.points[$i]"
                if (raw !is Map<*, *>) throw Bad(at, "an object is expected")
                KeyPoint(readStep(raw["time"], "$at.time"), readInt(raw["level"], "$at.level", 0..100))
            }.sortedBy { it.step }
            if (read.zipWithNext().any { (a, b) -> a.step == b.step }) throw Bad("$where.points", "two points at the same time")
            points[number - 1] = read
            when (val follows = entry["follows"]) {
                null -> Unit
                is Map<*, *> -> links[number - 1] = ChannelLink(
                    source = readInt(follows["channel"], "$where.follows.channel", 1..CHANNELS) - 1,
                    percent = readInt(follows["percent"], "$where.follows.percent", 0..100),
                )
                else -> throw Bad("$where.follows", "an object or null is expected")
            }
        }
        return try {
            ProgramSpec(name, points.map { it!! }, axis, links.toList()).pruned()
        } catch (e: IllegalArgumentException) {
            throw Bad("channels", e.message.orEmpty())
        }
    }

    private fun readSteps(raw: Any): List<List<Int>> {
        if (raw !is List<*> || raw.size != STEPS) throw Bad("steps", "$STEPS steps expected")
        return raw.mapIndexed { step, entry ->
            val where = "steps[$step]"
            val levels = when (entry) {
                is List<*> -> entry
                is Map<*, *> -> {
                    entry["time"]?.let { if (readStep(it, "$where.time") != step) throw Bad("$where.time", "steps must be in order from 00:00") }
                    entry["levels"] as? List<*> ?: throw Bad("$where.levels", "a list is expected")
                }
                else -> throw Bad(where, "a list or an object is expected")
            }
            if (levels.size != CHANNELS) throw Bad(where, "$CHANNELS levels expected")
            levels.mapIndexed { channel, level -> readInt(level, "$where.levels[$channel]", 0..100) }
        }
    }

    private fun readInt(raw: Any?, where: String, range: IntRange): Int {
        val value = (raw as? Long) ?: throw Bad(where, "a whole number is expected")
        if (value !in range.first.toLong()..range.last.toLong()) throw Bad(where, "${range.first} to ${range.last} expected")
        return value.toInt()
    }

    private val TIME = Regex("""^(\d{2}):(\d{2})$""")

    /** « HH:MM » on a half-hour → step. */
    private fun readStep(raw: Any?, where: String): Int {
        val quarter = readQuarter(raw, where)
        if (quarter % 2 != 0) throw Bad(where, "a time on the hour or half-hour is expected")
        return quarter / 2
    }

    /** « HH:MM » on a quarter-hour → quarter-hours since midnight. */
    private fun readQuarter(raw: Any?, where: String): Int {
        val match = (raw as? String)?.let { TIME.matchEntire(it) } ?: throw Bad(where, "a time HH:MM is expected")
        val hours = match.groupValues[1].toInt()
        val minutes = match.groupValues[2].toInt()
        if (hours > 23 || minutes % 15 != 0 || minutes > 45) throw Bad(where, "a time from 00:00 to 23:45 on a quarter-hour is expected")
        return hours * 4 + minutes / 15
    }
}
