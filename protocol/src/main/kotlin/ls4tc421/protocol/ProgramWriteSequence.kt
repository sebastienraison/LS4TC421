package ls4tc421.protocol

/** Where a program write is, and where a failed write can resume. */
sealed interface WriteStage {
    /** `01 01`: opening the write of one program (slot and name). */
    data object Start : WriteStage

    /** `01 02`: writing step [index] (0..47). */
    data class Step(val index: Int) : WriteStage

    /** `03 08`: ending the write of this program (acknowledged `83`). */
    data object EndProgram : WriteStage

    /** `04 0A`: closing the write session, once after its last program (acknowledged `91`). */
    data object Finish : WriteStage
}

/** One frame of a write plan, with the acknowledgement code that must come back. */
class PlannedCommand(val stage: WriteStage, val frame: ByteArray, val expectedAck: Int)

/**
 * THE place that defines the order of frames when writing programs.
 *
 * A write session, as the original app does it (read in its code, confirmed on a real controller on
 * 2026-11-16): for each program `01 01`, 48 x `01 02`, `03 08`; then a single `04 0A` after the last
 * program of the session. Sending `04 0A` after every program, then `01 01` again, made a real
 * controller ignore the `01 01` and then stop answering altogether (2026-11-16, docs/PROTOCOL.md).
 *
 * The pause the controller needs between the acknowledgement of `01 01` and the first step is a
 * matter of timing, not of order: [Tc421Client] applies it ([ClientConfig.firstStepDelay]).
 * Should another firmware need a different sequence, only this object has to change; the client
 * just walks the lists it returns.
 */
object ProgramWriteSequence {

    /**
     * The frames of one program: `01 01`, 48 x `01 02`, `03 08`, then `04 0A` when [endSession] is
     * `true` (the last program of its session, or a session of one program).
     */
    fun plan(mac: MacAddress, slot: Int, program: Program, endSession: Boolean = true): List<PlannedCommand> {
        Validation.slot(slot)
        val commands = ArrayList<PlannedCommand>(Limits.STEPS_PER_PROGRAM + 3)
        commands += PlannedCommand(WriteStage.Start, Frames.startDownload(mac, slot, program.name), AckCode.DOWNLOAD_START)
        program.steps.forEachIndexed { index, channels ->
            commands += PlannedCommand(WriteStage.Step(index), Frames.downloadStep(mac, index, channels), AckCode.DOWNLOAD_STEP)
        }
        commands += PlannedCommand(WriteStage.EndProgram, Frames.stopProcess(mac), AckCode.STOP)
        if (endSession) commands += closing(mac)
        return commands
    }

    /** `04 0A` alone: closes a session whose last program was written with `endSession = false`. */
    fun closing(mac: MacAddress): PlannedCommand =
        PlannedCommand(WriteStage.Finish, Frames.stopAllProcess(mac), AckCode.STOP_ALL)
}
