package net.rseb.ls4tc421.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.rseb.ls4tc421.data.ProgramRepository
import net.rseb.ls4tc421.model.ChannelLink
import net.rseb.ls4tc421.model.KeyPoint
import net.rseb.ls4tc421.model.ProgramSpec

data class EditorState(
    val loaded: Boolean = false,
    /** The program was deleted meanwhile (or never existed). */
    val missing: Boolean = false,
    val spec: ProgramSpec = ProgramSpec.empty("-"),
    /** As last saved: the editor is dirty when [spec] differs. */
    val saved: ProgramSpec = spec,
    val active: Int = 0,
    /** First step of the 3 h window of the main graph ([EditorRules]). */
    val windowStart: Int = EditorRules.EMPTY_CHANNEL_WINDOW_START,
    /** Step of the selected point of the active channel: the last point grabbed. */
    val selected: Int? = null,
    /** A point is being dragged: the graph shows its coordinates on the axes. */
    val dragging: Boolean = false,
    val canUndo: Boolean = false,
) {
    val dirty: Boolean get() = spec.pruned() != saved.pruned()
    val levels: List<List<Int>> by lazy { spec.levels() }

    /** Where « Add a point » would put a point; `null` = disabled. */
    val addTarget: KeyPoint? by lazy { EditorRules.addTarget(spec, levels, active, windowStart, selected) }
    val canDelete: Boolean get() = EditorRules.canDelete(spec, active, selected)
    val hint: EditorRules.Hint get() = EditorRules.hint(spec, active, windowStart)
}

/**
 * Screens 6-7: edits the key points and options of program [id]. Every change can be undone
 * (several levels); a drag or a slider move counts as one change. The graph shows a 3 h window that
 * only the overview scrolls (D19).
 */
class EditorViewModel(id: Long, private val programs: ProgramRepository) : ViewModel() {
    /** The program's id; it changes only if the program was deleted while being edited and saved again. */
    var id: Long = id
        private set

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    private val undo = ArrayDeque<ProgramSpec>()

    /** The drag in progress: the program and the point as they were when grabbed. */
    private var drag: Pair<ProgramSpec, KeyPoint>? = null

    init {
        viewModelScope.launch {
            val spec = programs.get(id)
            _state.value = if (spec == null) {
                EditorState(loaded = true, missing = true)
            } else {
                val active = firstEditable(spec)
                EditorState(
                    loaded = true,
                    spec = spec,
                    saved = spec,
                    active = active,
                    windowStart = EditorRules.initialWindowStart(spec.levels(), active),
                )
            }
        }
    }

    private fun firstEditable(spec: ProgramSpec) = (0 until 5).firstOrNull { !spec.isLinked(it) } ?: 0

    /** Starts a change: the current program goes on the undo stack. */
    fun begin() {
        undo.addLast(_state.value.spec)
        while (undo.size > MAX_UNDO) undo.removeFirst()
        _state.update { it.copy(canUndo = true) }
    }

    /** Updates the program within a change started by [begin]. */
    fun update(spec: ProgramSpec) = _state.update { it.copy(spec = spec).withKeptSelection() }

    /** A change in one go. */
    fun change(spec: ProgramSpec) {
        if (spec == _state.value.spec) return
        begin()
        update(spec)
    }

    /** Drops the last [begin] when the gesture changed nothing. */
    fun end() {
        val last = undo.lastOrNull() ?: return
        if (last == _state.value.spec) {
            undo.removeLast()
            _state.update { it.copy(canUndo = undo.isNotEmpty()) }
        }
    }

    fun undo() {
        if (drag != null) return
        val previous = undo.removeLastOrNull() ?: return
        _state.update { it.copy(spec = previous, canUndo = undo.isNotEmpty()).withKeptSelection() }
    }

    /** Another channel: the window goes back to its start position, the selection is cleared. */
    fun selectChannel(channel: Int) {
        if (drag != null) return
        _state.update {
            it.copy(active = channel, selected = null, windowStart = EditorRules.initialWindowStart(it.levels, channel))
        }
    }

    /** Moves the window (overview); a selection that leaves it is cleared. */
    fun moveWindow(start: Int) {
        if (drag != null) return
        _state.update { it.copy(windowStart = EditorRules.clampWindowStart(start)).withKeptSelection() }
    }

    // ---- drag of a point: grab, drag, release (one undo step) ----------------------------------

    /** Grabs the movable point at [step] of the active channel: it becomes the selection. */
    fun grab(step: Int): Boolean {
        val s = _state.value
        val point = EditorRules.movablePointsInWindow(s.spec, s.active, s.windowStart).firstOrNull { it.step == step } ?: return false
        drag = s.spec to point
        begin()
        _state.update { it.copy(selected = step, dragging = true) }
        return true
    }

    /** The finger has moved by [deltaSteps] and [deltaLevel] since the grab. */
    fun dragBy(deltaSteps: Float, deltaLevel: Float) {
        val (base, origin) = drag ?: return
        val s = _state.value
        val target = EditorRules.dragTarget(base, s.active, s.windowStart, origin, deltaSteps, deltaLevel)
        val moved = base.movedPoint(s.active, origin.step, target.step, target.level)
        _state.update { it.copy(spec = moved, selected = target.step) }
    }

    fun release() {
        if (drag == null) return
        drag = null
        _state.update { it.copy(dragging = false) }
        end()
    }

    // ---- buttons --------------------------------------------------------------------------------

    /** « Add a point » ([EditorRules.addTarget]); the new point becomes the selection. */
    fun addPoint() {
        if (drag != null) return
        val s = _state.value
        val target = s.addTarget ?: return
        change(s.spec.withPoint(s.active, target.step, target.level))
        _state.update { it.copy(selected = target.step) }
    }

    /** « Delete »: removes the selected point. */
    fun deleteSelected() {
        if (drag != null) return
        val s = _state.value
        val selected = s.selected ?: return
        if (!s.canDelete) return
        change(s.spec.withoutPoint(s.active, selected))
        _state.update { it.copy(selected = null) }
    }

    fun setSymmetry(on: Boolean) = change(_state.value.spec.withSymmetry(on))

    fun setLink(channel: Int, link: ChannelLink?) {
        val spec = _state.value.spec
        val next = try {
            spec.withLink(channel, link)
        } catch (_: IllegalArgumentException) {
            return // would make a chain: the screen does not offer it
        }
        change(next)
    }

    fun rename(name: String) = change(_state.value.spec.renamed(name))

    /** Saves; the bank then shows the new curve. */
    suspend fun save(): Long {
        val spec = _state.value.spec.pruned()
        id = programs.save(id, spec)
        _state.update { it.copy(spec = spec, saved = spec, missing = false) }
        return id
    }

    private fun EditorState.withKeptSelection(): EditorState =
        copy(selected = EditorRules.keptSelection(spec, active, windowStart, selected))

    private companion object {
        const val MAX_UNDO = 100
    }
}
