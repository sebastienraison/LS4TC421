package net.rseb.ls4tc421.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import net.rseb.ls4tc421.model.StoredForm

/** What the app knows of a slot that has a row (D31). No row = unknown. Stored as its name (TEXT). */
enum class SlotKind {
    /** A program written by the app (acknowledged). */
    WRITTEN,

    /** Emptied by the app (D30): a program at 0 % written over it. */
    EMPTIED,

    /** Declared empty by the user (« new or already emptied », when the device is added). */
    DECLARED_EMPTY,
}

/**
 * The app's memory of one slot of a controller (D31). The controller cannot be read back: a row is
 * only what this app wrote there, or what the user declared. Rows go with their device (cascade).
 */
@Entity(
    tableName = "slots",
    primaryKeys = ["deviceId", "slot"],
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["programId"])],
)
data class SlotEntity(
    val deviceId: Long,
    /** Protocol slot, 0..19 (shown 1..20). */
    val slot: Int,
    val state: SlotKind,
    /** Program of the bank that was written ([SlotKind.WRITTEN] only); it may have been edited or deleted since. */
    val programId: Long? = null,
    /** Name written on the controller ([SlotKind.WRITTEN], [SlotKind.EMPTIED]). */
    val name: String? = null,
    /** Epoch millis: end of the write ([SlotKind.WRITTEN], [SlotKind.EMPTIED]) or of the declaration ([SlotKind.DECLARED_EMPTY]). */
    val at: Long? = null,
    /** The 48 steps written ([StoredForm.levels]), [SlotKind.WRITTEN] only: « Replay on the light ». */
    val levels: String? = null,
)

/** The state of one slot, as the screens see it. [slot] is the protocol slot, 0..19. */
sealed interface SlotState {
    val slot: Int

    data class Unknown(override val slot: Int) : SlotState

    /** [levels] `null` if the stored steps cannot be read (it should not happen). */
    data class Written(
        override val slot: Int,
        val programId: Long?,
        val name: String,
        val at: Long,
        val levels: List<List<Int>>?,
    ) : SlotState

    data class Emptied(override val slot: Int, val name: String, val at: Long) : SlotState

    data class DeclaredEmpty(override val slot: Int, val at: Long) : SlotState

    /** The name on the controller, as far as the app knows it ([Written], [Emptied]); `null` otherwise. */
    val knownName: String?
        get() = when (this) {
            is Written -> name
            is Emptied -> name
            else -> null
        }
}

/** Number of program slots of a TC421. */
const val SLOT_COUNT = 20

fun SlotEntity.toState(): SlotState = when (state) {
    SlotKind.WRITTEN -> SlotState.Written(slot, programId, name.orEmpty(), at ?: 0L, levels?.let { StoredForm.parseLevels(it) })
    SlotKind.EMPTIED -> SlotState.Emptied(slot, name.orEmpty(), at ?: 0L)
    SlotKind.DECLARED_EMPTY -> SlotState.DeclaredEmpty(slot, at ?: 0L)
}

/** The 20 slots 0..19 in order, [SlotState.Unknown] where there is no row. Rows outside 0..19 are ignored. */
fun slotStates(rows: List<SlotEntity>): List<SlotState> {
    val bySlot = rows.associateBy { it.slot }
    return (0 until SLOT_COUNT).map { slot -> bySlot[slot]?.toState() ?: SlotState.Unknown(slot) }
}

/** Program [programId] is written on device [deviceId] ([deviceName]), slot [slot], at [at] (epoch millis). */
data class ProgramPlacement(val programId: Long, val deviceId: Long, val deviceName: String, val slot: Int, val at: Long?)
