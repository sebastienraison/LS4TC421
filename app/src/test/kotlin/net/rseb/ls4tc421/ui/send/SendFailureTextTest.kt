package net.rseb.ls4tc421.ui.send

import net.rseb.ls4tc421.link.UploadFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D22: which upload failures are followed by the restart advice. */
class SendFailureTextTest {
    @Test fun missingAcknowledgementAndNetworkErrorShowTheRestartAdvice() {
        assertTrue(showsRestartHint(UploadFailure.TimedOut(3)))
        assertTrue(showsRestartHint(UploadFailure.NetworkError))
    }

    @Test fun otherFailuresDoNotShowIt() {
        assertFalse(showsRestartHint(UploadFailure.UnexpectedAck(0x99, 0x81)))
        assertFalse(showsRestartHint(UploadFailure.UnknownDevice))
        assertFalse(showsRestartHint(UploadFailure.Cancelled))
    }
}
