package net.rseb.ls4tc421.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE id = :id")
    fun observe(id: Long): Flow<DeviceEntity?>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun get(id: Long): DeviceEntity?

    @Query("SELECT * FROM devices WHERE mac = :mac")
    suspend fun getByMac(mac: String): DeviceEntity?

    @Insert
    suspend fun insert(device: DeviceEntity): Long

    @Query("UPDATE devices SET address = :address, lastSeenAt = :seenAt WHERE id = :id")
    suspend fun markSeen(id: Long, address: String, seenAt: Long)

    @Query("UPDATE devices SET timeSetAt = :at WHERE id = :id")
    suspend fun markTimeSet(id: Long, at: Long)

    @Query("UPDATE devices SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** The slot declared in service (D16), or `null` to withdraw the declaration. */
    @Query("UPDATE devices SET activeSlot = :slot WHERE id = :id")
    suspend fun setActiveSlot(id: Long, slot: Int?)

    /** Its slot rows go with it (foreign key, cascade). */
    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: Long)
}
