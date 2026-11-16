package net.rseb.ls4tc421.model

/**
 * Compact text forms used in the database columns (not the exchange format: see [ProgramFile]).
 *
 * - points: one group per channel separated by `|`, each group `step:level` pairs separated by `,`
 *   (`17:0,18:20||…`);
 * - links: one entry per channel separated by `|`, `source:percent` (0-based source) or empty;
 * - levels: 48 groups separated by `;`, each 5 levels separated by `,`.
 */
object StoredForm {
    fun points(points: List<List<KeyPoint>>): String =
        points.joinToString("|") { list -> list.joinToString(",") { "${it.step}:${it.level}" } }

    fun parsePoints(text: String): List<List<KeyPoint>> {
        val groups = text.split('|')
        require(groups.size == CHANNELS) { "bad points column" }
        return groups.map { group ->
            if (group.isEmpty()) {
                emptyList()
            } else {
                group.split(',').map { pair ->
                    val (step, level) = pair.split(':').map { it.toInt() }
                    KeyPoint(step, level)
                }
            }
        }
    }

    fun links(links: List<ChannelLink?>): String =
        links.joinToString("|") { link -> link?.let { "${it.source}:${it.percent}" }.orEmpty() }

    fun parseLinks(text: String): List<ChannelLink?> {
        val groups = text.split('|')
        require(groups.size == CHANNELS) { "bad links column" }
        return groups.map { group ->
            if (group.isEmpty()) {
                null
            } else {
                val (source, percent) = group.split(':').map { it.toInt() }
                ChannelLink(source, percent)
            }
        }
    }

    fun levels(levels: List<List<Int>>): String = levels.joinToString(";") { it.joinToString(",") }

    fun parseLevels(text: String): List<List<Int>>? = try {
        val steps = text.split(';').map { step -> step.split(',').map { it.toInt() } }
        steps.takeIf { it.size == STEPS && it.all { s -> s.size == CHANNELS && s.all { v -> v in 0..100 } } }
    } catch (_: NumberFormatException) {
        null
    }
}
