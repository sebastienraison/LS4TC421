package ls4tc421.protocol

import java.net.InetAddress

/** A controller found by discovery, or addressed directly. */
data class Device(val address: InetAddress, val mac: MacAddress)

sealed interface DiscoveryResult {
    data class Found(val devices: List<Device>) : DiscoveryResult
    data object NoReply : DiscoveryResult
}

/** Outcome of a single command that expects an acknowledgement. */
sealed interface CommandResult {
    data object Success : CommandResult

    /** No acknowledgement after [attempts] tries. A plain timeout is not an exception. */
    data class TimedOut(val attempts: Int) : CommandResult

    /** A valid acknowledgement of this controller arrived, with another code than expected. */
    data class UnexpectedAck(val received: Int, val expected: Int) : CommandResult
}

/** Progress of a program write: [completed] of [total] frames acknowledged. */
data class WriteProgress(val completed: Int, val total: Int, val stage: WriteStage)

/** Outcome of a program write. A failure carries the [Failed.stage] at which to resume. */
sealed interface WriteResult {
    data object Success : WriteResult

    sealed interface Failed : WriteResult {
        /** Pass this as `resumeAt` to continue without resending what was acknowledged. */
        val stage: WriteStage
    }

    data class TimedOut(override val stage: WriteStage, val attempts: Int) : Failed
    data class UnexpectedAck(override val stage: WriteStage, val received: Int, val expected: Int) : Failed
}
