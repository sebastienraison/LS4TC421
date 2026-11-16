package net.rseb.ls4tc421.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.rseb.ls4tc421.model.ProgramSpec

/** A program of the bank, with what the list shows. */
data class BankEntry(
    val id: Long,
    val spec: ProgramSpec,
    val levels: List<List<Int>>,
    val updatedAt: Long,
    /** Names of the devices where this program is written (D31), each once. */
    val devices: List<String>,
    /** Where it is written: device and slot. */
    val placements: List<ProgramPlacement> = emptyList(),
)

/** The phone's bank of programs. */
class ProgramRepository(
    private val dao: ProgramDao,
    private val slots: SlotDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Every program; a row that cannot be read (it should not happen) is left out rather than crashing the list. */
    val bank: Flow<List<BankEntry>> = combine(dao.observeAll(), slots.observePlacements()) { programs, placements ->
        val byProgram = placements.groupBy { it.programId }
        programs.mapNotNull { row ->
            val spec = runCatching { row.toSpec() }.getOrNull() ?: return@mapNotNull null
            BankEntry(
                id = row.id,
                spec = spec,
                levels = spec.levels(),
                updatedAt = row.updatedAt,
                devices = byProgram[row.id].orEmpty().distinctBy { it.deviceId }.map { it.deviceName },
                placements = byProgram[row.id].orEmpty(),
            )
        }
    }

    /** Where program [id] is written (device and slot, D31), then after each change. */
    fun placements(id: Long): Flow<List<ProgramPlacement>> =
        slots.observePlacements().map { list -> list.filter { it.programId == id } }.distinctUntilChanged()

    suspend fun get(id: Long): ProgramSpec? = dao.get(id)?.let { runCatching { it.toSpec() }.getOrNull() }

    /** Program [id] as it is now, then after each change (rename, edit); `null` if it is gone or unreadable. */
    fun observe(id: Long): Flow<ProgramSpec?> =
        dao.observeAll().map { rows -> rows.firstOrNull { it.id == id }?.let { runCatching { it.toSpec() }.getOrNull() } }
            .distinctUntilChanged()

    /** Saves a new program; returns its id. */
    suspend fun create(spec: ProgramSpec): Long {
        val at = now()
        return dao.insert(ProgramEntity.from(spec, id = 0, createdAt = at, updatedAt = at))
    }

    /** Replaces program [id]; creates it again if it was deleted meanwhile. Returns the id in use. */
    suspend fun save(id: Long, spec: ProgramSpec): Long {
        val existing = dao.get(id) ?: return create(spec)
        dao.update(ProgramEntity.from(spec, id = id, createdAt = existing.createdAt, updatedAt = now()))
        return id
    }

    suspend fun duplicate(id: Long, name: String): Long? {
        val spec = get(id) ?: return null
        return create(spec.renamed(name))
    }

    suspend fun rename(id: Long, name: String) {
        val spec = get(id) ?: return
        save(id, spec.renamed(name))
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
