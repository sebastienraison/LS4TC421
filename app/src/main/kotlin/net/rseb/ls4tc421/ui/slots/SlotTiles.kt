package net.rseb.ls4tc421.ui.slots

import net.rseb.ls4tc421.data.SlotState

/** How a tile of the slot grid looks (D31): one look per state the app can know. */
enum class TileLook {
    /** Filled: a program written by the app. */
    WRITTEN,

    /** Filled too (the app wrote it), name in italics: a program at 0 % written by the app (D30). */
    EMPTIED,

    /** Dashed border: declared empty by the user. */
    DECLARED_EMPTY,

    /** Grey « ? »: the app does not know. */
    UNKNOWN,
}

/**
 * One tile of the slot grid. [slot] is the protocol slot 0..19 (shown 1..20). [name] is the name on
 * the controller as far as the app knows it, `null` when unknown or blank (a slot emptied « as new »
 * holds one space: it is shown as « empty »). [active]: the slot declared in service (D16).
 */
data class SlotTile(val slot: Int, val look: TileLook, val name: String?, val active: Boolean) {
    /** Shown as « empty » in secondary text: declared empty, or emptied under a blank name. */
    val showsEmpty: Boolean get() = look == TileLook.DECLARED_EMPTY || (look == TileLook.EMPTIED && name == null)
}

/** The 20 tiles of [states] (in slot order), [activeSlot] marked. */
fun slotTiles(states: List<SlotState>, activeSlot: Int?): List<SlotTile> = states.map { state ->
    val look = when (state) {
        is SlotState.Written -> TileLook.WRITTEN
        is SlotState.Emptied -> TileLook.EMPTIED
        is SlotState.DeclaredEmpty -> TileLook.DECLARED_EMPTY
        is SlotState.Unknown -> TileLook.UNKNOWN
    }
    SlotTile(state.slot, look, state.knownName?.takeIf { it.isNotBlank() }, state.slot == activeSlot)
}

/** Which sign a tile of the grid shows, by priority: a selection wins over « in service », which wins over the state. */
enum class TileEmphasis {
    /** Checked for emptying ([GridMode.SELECT_MANY]): error colours and a check. */
    CHECKED,

    /** Chosen in the send sheet ([GridMode.PICK_ONE]): primary container and a radio button. */
    CHOSEN,

    /** The slot declared in service: solid green, filled light bulb, no state shown. */
    IN_SERVICE,

    /** Anything else: the tile shows its state ([TileLook]). */
    STATE,
}

fun tileEmphasis(active: Boolean, mode: GridMode, marked: Boolean): TileEmphasis = when {
    marked && mode == GridMode.SELECT_MANY -> TileEmphasis.CHECKED
    marked && mode == GridMode.PICK_ONE -> TileEmphasis.CHOSEN
    active -> TileEmphasis.IN_SERVICE
    else -> TileEmphasis.STATE
}

/** The « In service, as you declared » card (D16, D31). */
sealed interface InService {
    /** No slot declared: « Slot not indicated », « Indicate ». */
    data object NotDeclared : InService

    /** [state] is what the app knows of the declared slot; [replay] the curve to replay, if the app wrote it. */
    data class Declared(val state: SlotState) : InService {
        val slot: Int get() = state.slot
        val replay: List<List<Int>>? get() = (state as? SlotState.Written)?.levels
        /** The name on the controller, if known and not blank. */
        val name: String? get() = state.knownName?.takeIf { it.isNotBlank() }
    }
}

/** The card for [activeSlot] (declared, or `null`) over the 20 [states]. */
fun inService(states: List<SlotState>, activeSlot: Int?): InService {
    val state = activeSlot?.let { slot -> states.firstOrNull { it.slot == slot } } ?: return InService.NotDeclared
    return InService.Declared(state)
}

/** What warns of the light going off when emptying (D30, D31). */
enum class LightWarning {
    /** No slot declared in service: the generic warning (« if one of them is in service… »). */
    GENERIC,

    /** The slot declared in service is selected: « The light will go off ». */
    ACTIVE_SELECTED,

    /** A slot is declared in service and it is not selected: no warning. */
    NONE,
}

fun lightWarning(selection: Set<Int>, activeSlot: Int?): LightWarning = when {
    activeSlot == null -> LightWarning.GENERIC
    activeSlot in selection -> LightWarning.ACTIVE_SELECTED
    else -> LightWarning.NONE
}

/** About 9 s per slot emptied (D30, measured on the controller for one slot). */
const val SECONDS_PER_SLOT = 9

/** A duration as it is announced: « About 30 seconds », « About 3 minutes ». */
sealed interface Approx {
    data class Seconds(val value: Int) : Approx
    data class Minutes(val value: Int) : Approx
}

/**
 * The announced duration for emptying [count] slots: under a minute, rounded to the nearest 10 s
 * (10 at least); from a minute, to the nearest minute.
 */
fun emptyingDuration(count: Int): Approx {
    val seconds = count.coerceAtLeast(1) * SECONDS_PER_SLOT
    return if (seconds < 60) {
        Approx.Seconds(((seconds + 5) / 10 * 10).coerceAtLeast(10))
    } else {
        Approx.Minutes((seconds + 30) / 60)
    }
}
