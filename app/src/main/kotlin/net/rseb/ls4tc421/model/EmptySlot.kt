package net.rseb.ls4tc421.model

import java.util.Locale
import ls4tc421.protocol.Program

/** The name written by « Empty a slot » (D30), chosen in Settings. */
enum class EmptySlotNaming(val key: String) {
    /** « Free 03 » / « Vide 03 »: the slot number as the app shows it, on two digits. The default. */
    NUMBERED("numbered"),

    /** One space (byte `20`): the controller's screen looks like a slot never written. */
    AS_NEW("as_new");

    companion object {
        /** The stored [key]; a missing or unknown key gives [NUMBERED]. */
        fun fromKey(key: String?): EmptySlotNaming = entries.firstOrNull { it.key == key } ?: NUMBERED
    }
}

/**
 * « Empty a slot » (D30): there is no command that clears one slot, so the app writes over it an
 * ordinary program at 0 % on the 5 channels and the 48 steps. That program is not in the bank.
 */
object EmptySlot {
    /** The « as new » name: one space, which the controller shows as nothing. */
    const val AS_NEW_NAME = " "

    /**
     * The name written in protocol slot [slot] (0..19). [numberedTemplate] is the translated
     * « Free %1$02d » of the app's language; the number is the one the app shows (1..20). Digits are
     * formatted with [Locale.ROOT], so that they stay ASCII whatever the phone's locale.
     */
    fun name(slot: Int, naming: EmptySlotNaming, numberedTemplate: String): String {
        require(slot in 0..19) { "slot $slot outside 0..19" }
        return when (naming) {
            EmptySlotNaming.NUMBERED -> String.format(Locale.ROOT, numberedTemplate, slot + 1)
            EmptySlotNaming.AS_NEW -> AS_NEW_NAME
        }
    }

    /** The 48 steps, every channel at 0. */
    val levels: List<List<Int>> = List(STEPS) { List(CHANNELS) { 0 } }

    /** The program written in [slot]. Throws if the name breaks the rule for sending (D23). */
    fun program(slot: Int, naming: EmptySlotNaming, numberedTemplate: String): Program =
        Program(name(slot, naming, numberedTemplate), levels)
}
