package net.rseb.ls4tc421.ui

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.runBlocking
import net.rseb.ls4tc421.FakeDeviceDao
import net.rseb.ls4tc421.FakeProgramDao
import net.rseb.ls4tc421.FakeSlotDao
import net.rseb.ls4tc421.await
import net.rseb.ls4tc421.data.ProgramRepository
import net.rseb.ls4tc421.model.Aquarium
import net.rseb.ls4tc421.model.KeyPoint
import net.rseb.ls4tc421.model.quarterTime
import net.rseb.ls4tc421.ui.editor.AXIS_SLIDER_TAG
import net.rseb.ls4tc421.ui.editor.EditorContent
import net.rseb.ls4tc421.ui.editor.EditorRules
import net.rseb.ls4tc421.ui.editor.EditorViewModel
import net.rseb.ls4tc421.ui.editor.GRAPH_TAG
import net.rseb.ls4tc421.ui.editor.GraphGeometry
import net.rseb.ls4tc421.ui.editor.OVERVIEW_TAG
import net.rseb.ls4tc421.ui.editor.OverviewGeometry
import net.rseb.ls4tc421.ui.theme.LsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor's gestures in Compose, on the JVM (Robolectric, no device): drag a point on the main
 * graph, touch empty space, touch the overview. Opens « aquarium » on C1, whose window is 08:30..11:30.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EditorComposeTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var vm: EditorViewModel

    @Before fun open() {
        val programs = ProgramRepository(FakeProgramDao(), FakeSlotDao(FakeDeviceDao()), now = { 1L })
        vm = runBlocking {
            val id = programs.create(Aquarium.RISE_ONLY)
            EditorViewModel(id, programs)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            LsTheme(darkTheme = false) { if (state.loaded) EditorContent(state, vm, Modifier) }
        }
        compose.waitUntil { vm.state.value.loaded }
        compose.waitForIdle()
        assertEquals(0, vm.state.value.active)
        assertEquals(17, vm.state.value.windowStart)
    }

    @Test fun draggingAPointMovesItWithoutScrollingThePage() {
        val graph = compose.onNodeWithTag(GRAPH_TAG)
        val before = graph.getBoundsInRoot()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        graph.performTouchInput {
            val g = GraphGeometry(width.toFloat(), height.toFloat(), density, 17)
            val start = Offset(g.x(19) + 10f, g.y(40)) // C1 at 09:30 is 40 %, pressed a little to the side
            down(start)
            // Up 20 % in small moves, as a finger would.
            repeat(10) { moveBy(Offset(0f, -2 * g.levelHeight)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(KeyPoint(19, 60), vm.state.value.spec.points[0].first { it.step == 19 })
        assertEquals(19, vm.state.value.selected)
        assertFalse(vm.state.value.dragging)
        assertEquals(before, graph.getBoundsInRoot())
        compose.onNodeWithText("Delete").assertIsEnabled()
        // One drag, one undo step.
        vm.undo()
        assertEquals(KeyPoint(19, 40), vm.state.value.spec.points[0].first { it.step == 19 })
        assertFalse(vm.state.value.canUndo)
    }

    @Test fun touchingEmptySpaceOnTheGraphDoesNothing() {
        compose.onNodeWithTag(GRAPH_TAG).performTouchInput {
            val g = GraphGeometry(width.toFloat(), height.toFloat(), density, 17)
            click(Offset(g.x(18), g.y(100))) // far above the curve
        }
        compose.waitForIdle()
        assertEquals(Aquarium.RISE_ONLY, vm.state.value.spec)
        assertNull(vm.state.value.selected)
        assertFalse(vm.state.value.canUndo)
    }

    @Test fun aDragStartedAwayFromAPointScrollsThePage() {
        val graph = compose.onNodeWithTag(GRAPH_TAG)
        val before = graph.getBoundsInRoot()
        graph.performTouchInput {
            val g = GraphGeometry(width.toFloat(), height.toFloat(), density, 17)
            down(Offset(g.x(18), g.y(100)))
            repeat(10) { moveBy(Offset(0f, -8 * g.levelHeight)) }
            up()
        }
        compose.waitForIdle()
        assertTrue(graph.getBoundsInRoot().top < before.top)
        assertEquals(Aquarium.RISE_ONLY, vm.state.value.spec)
        assertNull(vm.state.value.selected)
    }

    @Test fun touchingTheOverviewCentresTheWindowAndDraggingItScrolls() {
        val overview = compose.onNodeWithTag(OVERVIEW_TAG)
        overview.performTouchInput {
            val g = OverviewGeometry(width.toFloat(), height.toFloat(), density)
            click(Offset(g.x(42), height / 2f)) // 21:00
        }
        compose.waitForIdle()
        assertEquals(39, vm.state.value.windowStart)
        overview.performTouchInput {
            val g = OverviewGeometry(width.toFloat(), height.toFloat(), density)
            val inside = Offset((g.x(39) + g.x(45)) / 2, height / 2f)
            down(inside)
            repeat(10) { moveBy(Offset(-(g.x(1) - g.x(0)), 0f)) } // 5 h earlier
            up()
        }
        compose.waitForIdle()
        assertEquals(29, vm.state.value.windowStart)
        // Touching near the end of the day: the window stops at 21:00..24:00.
        overview.performTouchInput {
            val g = OverviewGeometry(width.toFloat(), height.toFloat(), density)
            click(Offset(g.x(47), height / 2f))
        }
        compose.waitForIdle()
        assertEquals(EditorRules.MAX_WINDOW_START, vm.state.value.windowStart)
    }

    // ---- D20: the overview shows the symmetry axis and stays pinned above the scrolling part ----

    private fun overviewDescription(): String =
        compose.onNodeWithTag(OVERVIEW_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

    @Test fun theOverviewGetsTheAxisOnlyWithSymmetryOn() {
        // « aquarium » opens with symmetry on.
        val axis = vm.state.value.spec.symmetryAxis!!
        assertTrue(overviewDescription(), overviewDescription().endsWith("Symmetry axis at ${quarterTime(axis)}."))
        compose.runOnIdle { vm.setSymmetry(false) }
        compose.waitForIdle()
        assertFalse(overviewDescription(), overviewDescription().contains("Symmetry axis"))
        compose.runOnIdle { vm.setSymmetry(true) }
        compose.waitForIdle()
        val again = vm.state.value.spec.symmetryAxis!!
        assertTrue(overviewDescription(), overviewDescription().endsWith("Symmetry axis at ${quarterTime(again)}."))
    }

    @Test fun theAxisFollowsTheSliderBeforeTheFingerIsLifted() {
        val before = vm.state.value.spec.symmetryAxis!! // « aquarium » opens with symmetry on
        val slider = compose.onNodeWithTag(AXIS_SLIDER_TAG)
        slider.performScrollTo()
        slider.performTouchInput {
            down(Offset(width * before / 95f, height / 2f))
            repeat(10) { moveBy(Offset(width / 40f, 0f)) } // about 6 h later, finger still down
        }
        compose.waitForIdle()
        val during = vm.state.value.spec.symmetryAxis!!
        assertTrue("axis $before -> $during", during > before)
        assertTrue(overviewDescription().endsWith("Symmetry axis at ${quarterTime(during)}."))
        slider.performTouchInput { up() }
        compose.waitForIdle()
        // The whole slider move is one undo step.
        compose.runOnIdle { vm.undo() }
        assertEquals(before, vm.state.value.spec.symmetryAxis)
    }

    @Test fun chipsAndOverviewStayInSightWhenScrolledToTheAxis() {
        val graph = compose.onNodeWithTag(GRAPH_TAG)
        val overview = compose.onNodeWithTag(OVERVIEW_TAG)
        val graphBefore = graph.getBoundsInRoot()
        val overviewBefore = overview.getBoundsInRoot()
        compose.onNodeWithTag(AXIS_SLIDER_TAG).performScrollTo().assertIsDisplayed()
        assertTrue("the column has scrolled", graph.getBoundsInRoot().top < graphBefore.top)
        compose.onNodeWithText("C1").assertIsDisplayed()
        compose.onNodeWithText("C5").assertIsDisplayed()
        overview.assertIsDisplayed()
        assertEquals(overviewBefore, overview.getBoundsInRoot())
    }
}
