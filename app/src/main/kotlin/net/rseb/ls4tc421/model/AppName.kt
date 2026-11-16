package net.rseb.ls4tc421.model

import ls4tc421.protocol.ProgramName

/**
 * The app's rule for a program name (D23, amended on 2026-11-16): the library's rule ([ProgramName]:
 * printable ASCII, what the controller accepts, up to 9 characters), shortened to [MAX_LENGTH]
 * characters, because the controller's screen shows only the first 7 characters of a name
 * (docs/PROTOCOL.md, « Program names »). The library keeps 9: it describes the controller; the app
 * applies what the user can read.
 */
object AppName {
    const val MAX_LENGTH = 7

    /** What is wrong with [name] for the app, or null when it can be sent as is. */
    fun check(name: String): ProgramName.Problem? {
        ProgramName.check(name)?.let { return it }
        val length = name.codePointCount(0, name.length)
        return if (length > MAX_LENGTH) ProgramName.Problem.TooLong(length) else null
    }

    fun isValid(name: String): Boolean = check(name) == null
}
