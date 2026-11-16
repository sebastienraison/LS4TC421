package net.rseb.ls4tc421.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.rseb.ls4tc421.FakeDeviceDao
import net.rseb.ls4tc421.FakeProgramDao
import net.rseb.ls4tc421.FakeSlotDao
import net.rseb.ls4tc421.model.Aquarium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The send sheet follows the bank ([ProgramRepository.observe]) and reads the program again when
 * sending. Regression of 15/11: a program renamed after a first opening of the sheet was still sent
 * under its old name, because the sheet's view model had read it once and outlived the sheet.
 */
class ProgramObserveTest {
    private val programs = ProgramRepository(FakeProgramDao(), FakeSlotDao(FakeDeviceDao()), now = { 1L })

    @Test fun aRenameReachesWhatTheSheetShowsAndSends() = runBlocking {
        val id = programs.create(Aquarium.RISE_ONLY.renamed("court_basique"))
        assertEquals("court_basique", programs.observe(id).first()?.name)

        programs.rename(id, "court")

        assertEquals("court", programs.observe(id).first()?.name)
        assertEquals("court", programs.get(id)?.name)
        assertEquals("court", programs.get(id)?.toProgram()?.name)
    }

    @Test fun anEditedCurveReachesWhatTheSheetSends() = runBlocking {
        val id = programs.create(Aquarium.RISE_ONLY)
        val edited = Aquarium.RISE_ONLY.withAxis(Aquarium.RISE_ONLY.symmetryAxis?.plus(4) ?: 60)
        programs.save(id, edited)

        assertEquals(edited, programs.observe(id).first())
        assertEquals(edited.levels(), programs.get(id)?.levels())
    }

    @Test fun aDeletedProgramIsNoLongerOffered() = runBlocking {
        val id = programs.create(Aquarium.RISE_ONLY)
        programs.delete(id)
        assertNull(programs.observe(id).first())
    }
}
