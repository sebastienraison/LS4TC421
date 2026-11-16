package net.rseb.ls4tc421.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.rseb.ls4tc421.FakeDeviceDao
import net.rseb.ls4tc421.FakeProgramDao
import net.rseb.ls4tc421.FakeSlotDao
import net.rseb.ls4tc421.await
import net.rseb.ls4tc421.data.ProgramRepository
import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.model.ChannelLink
import net.rseb.ls4tc421.model.KeyPoint
import net.rseb.ls4tc421.model.ProgramSpec
import net.rseb.ls4tc421.ui.editor.EditorRules
import net.rseb.ls4tc421.ui.editor.EditorViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val programs = ProgramRepository(FakeProgramDao(), FakeSlotDao(FakeDeviceDao()), now = { 1L })

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun open(spec: ProgramSpec): EditorViewModel = runBlocking {
        val id = programs.create(spec)
        EditorViewModel(id, programs).also { vm -> vm.state.await { it.loaded } }
    }

    @Test fun aDragCountsAsOneUndoStepAndUndoClearsASelectionItRemoves() = runBlocking {
        val vm = open(ProgramSpec("t", List(5) { if (it == 0) listOf(KeyPoint(10, 20), KeyPoint(20, 80)) else emptyList() }))
        vm.moveWindow(15) // 07:30..10:30
        assertTrue(vm.grab(20))
        assertEquals(20, vm.state.value.selected)
        assertTrue(vm.state.value.dragging)
        vm.dragBy(2f, -10f) // R2: stops at 10:30
        vm.dragBy(1f, -20f)
        vm.release()
        assertFalse(vm.state.value.dragging)
        assertEquals(listOf(KeyPoint(10, 20), KeyPoint(21, 60)), vm.state.value.spec.points[0])
        assertEquals(21, vm.state.value.selected)
        vm.undo()
        assertEquals(listOf(KeyPoint(10, 20), KeyPoint(20, 80)), vm.state.value.spec.points[0])
        assertNull(vm.state.value.selected) // the point at 21 is gone
        assertFalse(vm.state.value.canUndo)
    }

    @Test fun aGrabWithoutMoveSelectsAndLeavesNoUndoStep() {
        val vm = open(Aquarium.RISE_ONLY)
        vm.selectChannel(2)
        assertEquals(16, vm.state.value.windowStart)
        assertTrue(vm.grab(19))
        vm.dragBy(0.1f, 1f)
        vm.release()
        assertEquals(19, vm.state.value.selected)
        assertFalse(vm.state.value.canUndo)
        assertFalse(vm.state.value.dirty)
        // Points that cannot be grabbed: outside the window, or not there.
        assertFalse(vm.grab(23))
        assertFalse(vm.grab(30))
    }

    @Test fun theSelectionIsClearedWhenItLeavesTheWindowOrTheChannelChanges() {
        val vm = open(Aquarium.RISE_ONLY)
        vm.selectChannel(2)
        vm.grab(17)
        vm.release()
        vm.moveWindow(17) // 17 still in
        assertEquals(17, vm.state.value.selected)
        vm.moveWindow(18) // 17 left the window
        assertNull(vm.state.value.selected)
        vm.grab(19)
        vm.release()
        vm.selectChannel(0)
        assertNull(vm.state.value.selected)
        assertEquals(17, vm.state.value.windowStart) // C1's own start position
    }

    @Test fun addThenDeleteTheSelectedPoint() {
        val vm = open(ProgramSpec.empty("t"))
        assertEquals(12, vm.state.value.windowStart)
        assertFalse(vm.state.value.canDelete)
        vm.addPoint() // middle of 06:00..09:00
        assertEquals(listOf(KeyPoint(15, 0)), vm.state.value.spec.points[0])
        assertEquals(15, vm.state.value.selected)
        vm.addPoint() // 30 min after the selection
        assertEquals(listOf(KeyPoint(15, 0), KeyPoint(16, 0)), vm.state.value.spec.points[0])
        assertEquals(16, vm.state.value.selected)
        vm.deleteSelected()
        assertEquals(listOf(KeyPoint(15, 0)), vm.state.value.spec.points[0])
        assertNull(vm.state.value.selected)
        vm.undo()
        vm.undo()
        vm.undo()
        assertEquals(emptyList<KeyPoint>(), vm.state.value.spec.points[0])
    }

    /** Non-regression: the rise of « aquarium », entered by Add and simulated drags, gives back its 48 steps. */
    @Test fun aquariumRiseEnteredByDragGivesBackItsKeyPoints() {
        val vm = open(ProgramSpec.empty("aquarium"))
        vm.setSymmetry(true)
        vm.begin() // the axis slider
        vm.update(vm.state.value.spec.withAxis(Aquarium.AXIS))
        vm.end()
        for (channel in 0 until 5) {
            vm.selectChannel(channel)
            val target = Aquarium.RISE_ONLY.points[channel]
            // First point: Add (middle of the window), then drag it to 08:00 and its level.
            vm.addPoint()
            val first = vm.state.value.selected!!
            assertTrue(vm.grab(first))
            vm.dragBy((target[0].step - first).toFloat(), (target[0].level - levelOf(vm, channel, first)).toFloat())
            vm.release()
            for (point in target.drop(1)) {
                val window = vm.state.value.windowStart
                if (point.step > window + EditorRules.WINDOW_STEPS) vm.moveWindow(point.step - EditorRules.WINDOW_STEPS)
                vm.addPoint() // 30 min after the selection, on the curve
                assertEquals(point.step, vm.state.value.selected)
                assertTrue(vm.grab(point.step))
                vm.dragBy(0f, (point.level - levelOf(vm, channel, point.step)).toFloat())
                vm.release()
            }
            assertEquals(target, vm.state.value.spec.points[channel])
        }
        assertEquals(Aquarium.RISE_ONLY.copy(name = "aquarium"), vm.state.value.spec.pruned())
        assertEquals(Aquarium.STEPS, vm.state.value.levels)
    }

    private fun levelOf(vm: EditorViewModel, channel: Int, step: Int): Int =
        vm.state.value.spec.points[channel].first { it.step == step }.level

    @Test fun aGestureThatChangesNothingLeavesNoUndoStep() {
        val vm = open(ProgramSpec.empty("t"))
        vm.begin()
        vm.end()
        assertFalse(vm.state.value.canUndo)
    }

    @Test fun dirtyUntilSavedAndTheBankHoldsThePrunedProgram() = runBlocking {
        val vm = open(Aquarium.RISE_ONLY)
        assertFalse(vm.state.value.dirty)
        vm.setLink(1, ChannelLink(0, 90))
        assertTrue(vm.state.value.dirty)
        vm.save()
        assertFalse(vm.state.value.dirty)
        assertEquals(Aquarium.RISE_ONLY.withLink(1, ChannelLink(0, 90)), programs.get(vm.id))
    }

    @Test fun linkedOrMirrorSideEditsAreRefused() {
        val vm = open(Aquarium.RISE_ONLY.withLink(1, ChannelLink(0, 90)))
        vm.selectChannel(1)
        assertFalse(vm.grab(19)) // linked channel
        vm.addPoint()
        vm.deleteSelected()
        vm.selectChannel(0)
        vm.moveWindow(40) // the fall: mirrors only
        vm.addPoint() // 21:30 is after the axis
        assertFalse(vm.grab(42))
        vm.setLink(0, ChannelLink(2, 50)) // channel 1 is a source: would make a chain
        assertFalse(vm.state.value.canUndo)
        assertEquals(Aquarium.RISE_ONLY.withLink(1, ChannelLink(0, 90)), vm.state.value.spec)
    }
}
