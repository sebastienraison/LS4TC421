package net.rseb.ls4tc421.ui.slots

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import net.rseb.ls4tc421.R
import net.rseb.ls4tc421.data.SLOT_COUNT
import net.rseb.ls4tc421.data.SlotState
import net.rseb.ls4tc421.ui.formatDateTime

/** « tropical », « empty » or « unknown »: what a slot holds, in a word or its name. */
@Composable
fun slotContentWord(state: SlotState): String {
    val name = state.knownName?.takeIf { it.isNotBlank() }
    return when {
        name != null -> name
        state is SlotState.Unknown -> stringResource(R.string.slot_unknown_word)
        else -> stringResource(R.string.slot_empty_word)
    }
}

/** « Slot 5 · tropical » (sheet of a slot). */
@Composable
fun slotTitle(state: SlotState): String = stringResource(R.string.slot_title, state.slot + 1, slotContentWord(state))

/** « Written by the app on … », « Emptied by the app on … », « Declared empty on … », « Content unknown to the app ». */
@Composable
fun slotStateLine(state: SlotState): String = when (state) {
    is SlotState.Written -> stringResource(R.string.slot_state_written, formatDateTime(state.at))
    is SlotState.Emptied -> stringResource(R.string.slot_state_emptied, formatDateTime(state.at))
    is SlotState.DeclaredEmpty -> stringResource(R.string.slot_state_declared_empty, formatDateTime(state.at))
    is SlotState.Unknown -> stringResource(R.string.slot_state_unknown)
}

/** « 4 », « 4 and 6 », « 4, 6 and 7 » (slots shown 1..20). */
@Composable
fun slotList(slots: List<Int>): String {
    val shown = slots.sorted().map { (it + 1).toString() }
    return if (shown.size <= 1) shown.joinToString() else stringResource(R.string.list_and, shown.dropLast(1).joinToString(", "), shown.last())
}

/** « The slots 4, 6 and 7 get a program at 0 %. » */
@Composable
fun emptyingWho(slots: Set<Int>): String = when {
    slots.size == 1 -> stringResource(R.string.empty_gets_one, slots.first() + 1)
    slots.size == SLOT_COUNT -> stringResource(R.string.empty_gets_all, SLOT_COUNT)
    else -> stringResource(R.string.empty_gets_some, slotList(slots.toList()))
}

/** « About 30 seconds. », « About 3 minutes, near the controller. » */
@Composable
fun emptyingDurationText(count: Int): String = when (val d = emptyingDuration(count)) {
    is Approx.Seconds -> pluralStringResource(R.plurals.empty_about_seconds, d.value, d.value)
    is Approx.Minutes -> pluralStringResource(R.plurals.empty_about_minutes, d.value, d.value)
}
