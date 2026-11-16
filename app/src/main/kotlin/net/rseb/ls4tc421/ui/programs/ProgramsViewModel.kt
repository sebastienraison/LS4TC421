package net.rseb.ls4tc421.ui.programs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import net.rseb.ls4tc421.data.BankEntry
import net.rseb.ls4tc421.data.ProgramRepository
import net.rseb.ls4tc421.model.ProgramSpec

/** The bank (screen 5). The list is `null` until the database answered. */
class ProgramsViewModel(private val programs: ProgramRepository) : ViewModel() {
    val bank: StateFlow<List<BankEntry>?> = programs.bank.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** New program, empty or a copy of [copyOf]; returns its id. */
    suspend fun create(name: String, copyOf: Long?): Long? =
        if (copyOf == null) programs.create(ProgramSpec.empty(name)) else programs.duplicate(copyOf, name)

    suspend fun duplicate(id: Long, name: String): Long? = programs.duplicate(id, name)

    suspend fun rename(id: Long, name: String) = programs.rename(id, name)

    suspend fun delete(id: Long) = programs.delete(id)

    suspend fun import(spec: ProgramSpec): Long = programs.create(spec)
}
