package net.rseb.ls4tc421.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.rseb.ls4tc421.model.ProgramSpec
import net.rseb.ls4tc421.model.StoredForm

/**
 * A program of the phone's bank. The key points and options are the source; the 48 steps are
 * computed from them ([ProgramSpec.levels]) and not stored. Text forms: [StoredForm].
 */
@Entity(tableName = "programs")
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Key points of the 5 channels ([StoredForm.points]). */
    val points: String,
    /** Symmetry axis in quarter-hours since midnight; null = no symmetry. */
    val symmetryAxis: Int?,
    /** Linked channels ([StoredForm.links]). */
    val links: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toSpec(): ProgramSpec = ProgramSpec(name, StoredForm.parsePoints(points), symmetryAxis, StoredForm.parseLinks(links))

    companion object {
        fun from(spec: ProgramSpec, id: Long, createdAt: Long, updatedAt: Long): ProgramEntity {
            val clean = spec.pruned()
            return ProgramEntity(
                id = id,
                name = clean.name,
                points = StoredForm.points(clean.points),
                symmetryAxis = clean.symmetryAxis,
                links = StoredForm.links(clean.links),
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
    }
}
