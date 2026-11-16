package net.rseb.ls4tc421.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The slots table (D31). The abstract members are the primitives; what they mean (written, emptied,
 * declared empty) is in the open members built on them, so that a test stand-in only replaces the
 * primitives.
 */
@Dao
abstract class SlotDao {
    /** The rows of device [deviceId], by slot; slots without a row are unknown. */
    @Query("SELECT * FROM slots WHERE deviceId = :deviceId ORDER BY slot")
    abstract fun observe(deviceId: Long): Flow<List<SlotEntity>>

    @Query("SELECT * FROM slots WHERE deviceId = :deviceId ORDER BY slot")
    abstract suspend fun get(deviceId: Long): List<SlotEntity>

    /**
     * Replaces the row of ([deviceId], [slot]), whatever it held. Nothing is written if the device is
     * gone (deleted while an upload ran): the same no-op as the former `UPDATE devices`, instead of a
     * foreign key failure.
     */
    @Query(
        "INSERT OR REPLACE INTO slots (deviceId, slot, state, programId, name, at, levels) " +
            "SELECT :deviceId, :slot, :state, :programId, :name, :at, :levels " +
            "WHERE EXISTS (SELECT 1 FROM devices WHERE id = :deviceId)",
    )
    abstract suspend fun put(deviceId: Long, slot: Int, state: SlotKind, programId: Long?, name: String?, at: Long?, levels: String?)

    /** « I no longer know what it holds »: the slot goes back to unknown. Returns the rows removed. */
    @Query("DELETE FROM slots WHERE deviceId = :deviceId AND slot = :slot")
    abstract suspend fun forget(deviceId: Long, slot: Int): Int

    /** Every slot where a program of the bank is written, with its device's name (Programs tab, send sheet). */
    @Query(
        "SELECT s.programId AS programId, s.deviceId AS deviceId, d.name AS deviceName, s.slot AS slot, s.at AS at " +
            "FROM slots s JOIN devices d ON d.id = s.deviceId " +
            "WHERE s.state = 'WRITTEN' AND s.programId IS NOT NULL " +
            "ORDER BY d.name COLLATE NOCASE, d.id, s.slot",
    )
    abstract fun observePlacements(): Flow<List<ProgramPlacement>>

    /** The 20 slots of device [deviceId], unknown ones included, then after each change. */
    fun observeStates(deviceId: Long): Flow<List<SlotState>> = observe(deviceId).map(::slotStates)

    /** A program was written by the app in [slot] (acknowledged upload). */
    open suspend fun recordWritten(deviceId: Long, slot: Int, programId: Long?, name: String, at: Long, levels: String) =
        put(deviceId, slot, SlotKind.WRITTEN, programId, name, at, levels)

    /** [slot] was emptied by the app (D30), under [name]. */
    open suspend fun recordEmptied(deviceId: Long, slot: Int, name: String, at: Long) =
        put(deviceId, slot, SlotKind.EMPTIED, null, name, at, null)

    /** « New or already emptied »: the 20 slots are declared empty, whatever was known before. */
    @Transaction
    open suspend fun declareAllEmpty(deviceId: Long, at: Long) {
        for (slot in 0 until SLOT_COUNT) put(deviceId, slot, SlotKind.DECLARED_EMPTY, null, null, at, null)
    }
}
