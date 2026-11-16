package ls4tc421.protocol

/** Limits of the controller, as described in docs/PROTOCOL.md. */
object Limits {
    const val CHANNELS = 5
    const val STEPS_PER_PROGRAM = 48
    const val STEP_MINUTES = 30
    const val MAX_LEVEL = 100
    const val MIN_SLOT = 0
    const val MAX_SLOT = 19

    /** Longest SSID or passphrase accepted (the original app truncates to 32 bytes). */
    const val MAX_WIFI_BYTES = 32

    /**
     * Longest program or preview name, in characters (one byte each: names are printable ASCII,
     * see [ProgramName]). The controller refuses a `01 01` whose name is 10 bytes or more (ack `87`).
     */
    const val MAX_NAME_LENGTH = 9
}

/**
 * The rule for a program or preview name, shared by the library and the app: 1 to
 * [Limits.MAX_NAME_LENGTH] characters, all printable ASCII (0x20..0x7E). The controller's screen
 * shows only ASCII, and it refuses longer names. Names are sent as ASCII, one byte per character.
 */
object ProgramName {
    const val FIRST_PRINTABLE = 0x20
    const val LAST_PRINTABLE = 0x7E

    /** Why a name cannot be sent. */
    sealed interface Problem {
        data object Empty : Problem

        /** [length] characters (code points), more than [Limits.MAX_NAME_LENGTH]. */
        data class TooLong(val length: Int) : Problem

        /** [character] is the first character outside printable ASCII (a whole code point). */
        data class Refused(val character: String) : Problem
    }

    /**
     * What is wrong with [name], or null when it can be sent as is. A refused character is reported
     * before the length: shortening the name would not make it sendable.
     */
    fun check(name: String): Problem? {
        if (name.isEmpty()) return Problem.Empty
        val refused = name.codePoints().filter { it !in FIRST_PRINTABLE..LAST_PRINTABLE }.findFirst()
        if (refused.isPresent) return Problem.Refused(String(Character.toChars(refused.asInt)))
        val length = name.codePointCount(0, name.length)
        if (length > Limits.MAX_NAME_LENGTH) return Problem.TooLong(length)
        return null
    }

    fun isValid(name: String): Boolean = check(name) == null
}

/**
 * A daily program: 48 steps of 30 minutes (step 0 = 00:00), 5 channels per step, 0..100 %.
 * The constructor validates everything, so a [Program] is always sendable.
 */
class Program(val name: String, steps: List<List<Int>>) {
    val steps: List<List<Int>> = steps.map { it.toList() }

    init {
        Validation.name(name)
        require(this.steps.size == Limits.STEPS_PER_PROGRAM) {
            "A program has exactly ${Limits.STEPS_PER_PROGRAM} steps, got ${this.steps.size}"
        }
        this.steps.forEachIndexed { index, channels ->
            Validation.channels(channels, "step $index")
        }
    }
}

internal object Validation {
    fun name(name: String) {
        val problem = ProgramName.check(name) ?: return
        val detail = when (problem) {
            ProgramName.Problem.Empty -> "it is empty"
            is ProgramName.Problem.TooLong -> "it has ${problem.length}"
            is ProgramName.Problem.Refused -> "\"${problem.character}\" is not printable ASCII"
        }
        throw IllegalArgumentException(
            "Name must be 1..${Limits.MAX_NAME_LENGTH} printable ASCII characters (0x20..0x7E): $detail",
        )
    }

    fun channels(channels: List<Int>, what: String) {
        require(channels.size == Limits.CHANNELS) {
            "$what: exactly ${Limits.CHANNELS} channels expected, got ${channels.size}"
        }
        channels.forEachIndexed { channel, level ->
            require(level in 0..Limits.MAX_LEVEL) {
                "$what: channel ${channel + 1} level $level is outside 0..${Limits.MAX_LEVEL}"
            }
        }
    }

    fun slot(slot: Int) {
        require(slot in Limits.MIN_SLOT..Limits.MAX_SLOT) {
            "Program slot must be ${Limits.MIN_SLOT}..${Limits.MAX_SLOT}, got $slot"
        }
    }

    fun step(step: Int) {
        require(step in 0 until Limits.STEPS_PER_PROGRAM) {
            "Step must be 0..${Limits.STEPS_PER_PROGRAM - 1}, got $step"
        }
    }

    fun wifiText(text: String, what: String) {
        val size = text.toByteArray(Charsets.UTF_8).size
        require(size in 1..Limits.MAX_WIFI_BYTES) {
            "$what must be 1..${Limits.MAX_WIFI_BYTES} bytes in UTF-8, got $size"
        }
    }
}
