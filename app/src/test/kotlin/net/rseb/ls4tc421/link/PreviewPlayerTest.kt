package net.rseb.ls4tc421.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ls4tc421.protocol.Device
import ls4tc421.protocol.FrameDirection
import ls4tc421.protocol.FrameListener
import net.rseb.ls4tc421.TestEnv
import net.rseb.ls4tc421.data.ClientSource
import net.rseb.ls4tc421.model.Aquarium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview with the real client against the simulator, in virtual time: the client's I/O runs on
 * the test dispatcher, so every frame is recorded with the virtual time at which it was sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewPlayerTest {
    private class Sent(val at: Long, val cmd: Int, val sub: Int, val levels: List<Int>)

    private suspend fun TestScope.withPlayer(
        name: String = "aqua",
        block: suspend TestScope.(PreviewPlayer, List<Sent>, TestEnv) -> Unit,
    ) {
        val env = TestEnv(ioDispatcher = StandardTestDispatcher(testScheduler))
        val sent = ArrayList<Sent>()
        env.frames.add(FrameListener { e ->
            if (e.direction == FrameDirection.SENT) {
                val b = e.bytes
                sent += Sent(testScheduler.currentTime, b[2].toInt() and 0xFF, b[3].toInt() and 0xFF, (6..10).map { b[it].toInt() and 0xFF })
            }
        })
        val device = Device(env.loopback, env.mac)
        val player = PreviewPlayer(ClientSource { env.client }, { device }, name, Aquarium.STEPS, env.guard, backgroundScope)
        try {
            this.block(player, sent, env)
        } finally {
            env.close()
        }
    }

    private fun List<Sent>.values() = filter { it.cmd == 0x06 && it.sub == 0x02 }

    @Test fun startsWith0601ThenOne0602PerSecondFrom30MinutesBeforeTheLight() = runTest {
        withPlayer { player, sent, _ ->
            player.start()
            runCurrent()
            assertEquals(0x06 to 0x01, sent.first().cmd to sent.first().sub)
            assertEquals(8 * 60, player.startMinute) // lit from 08:30
            advanceTimeBy(5_000)
            runCurrent()
            val values = sent.values()
            assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L), values.map { it.at })
            assertEquals(listOf(0, 0, 0, 0, 0), values[0].levels) // 08:00
            assertEquals(listOf(0, 0, 6, 2, 0), values[1].levels) // 08:12: 12/30 of the way to 08:30
            assertEquals(listOf(0, 0, 12, 4, 0), values[2].levels) // 08:24
            assertEquals(8 * 60 + 5 * 12, player.state.value.minuteOfDay)
            player.end()
        }
    }

    @Test fun theWholeDayTakesTwoMinutesThenTheLastFrameKeepsBeingSent() = runTest {
        withPlayer { player, sent, _ ->
            player.start()
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(PreviewPlayer.DAY, player.state.value.position)
            assertFalse(player.state.value.playing)
            assertEquals(8 * 60, player.state.value.minuteOfDay) // back to the start time, a day later
            val before = sent.values().size
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(before + 3, sent.values().size)
            player.end()
        }
    }

    @Test fun pausedTheSameFrameIsResentEverySecondAndSeekMoves() = runTest {
        withPlayer { player, sent, _ ->
            player.start()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            player.pause()
            val frozen = player.state.value.levels
            advanceTimeBy(3_000)
            runCurrent()
            val last = sent.values().takeLast(3)
            assertEquals(listOf(3_000L, 4_000L, 5_000L), last.map { it.at })
            assertTrue(last.all { it.levels == frozen })
            player.seek(15 * 60)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(100, 90, 100, 100, 0), sent.values().last().levels)
            assertEquals(15 * 60, player.state.value.minuteOfDay)
            player.end()
        }
    }

    @Test fun endSends0308AndStopsTheFrames() = runTest {
        withPlayer { player, sent, env ->
            player.start()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            player.end()
            runCurrent()
            assertEquals(0x03 to 0x08, sent.last().cmd to sent.last().sub)
            assertEquals(PreviewEnd.Acknowledged, player.state.value.end)
            assertFalse(player.state.value.open)
            val count = sent.size
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("no frame after the end", count, sent.size)
            assertTrue(env.guard.inUse == null)
            val until = System.currentTimeMillis() + 2_000
            while (env.sim.previewActive && System.currentTimeMillis() < until) Thread.sleep(10)
            assertFalse(env.sim.previewActive)
        }
    }

    @Test fun leavingEndsAnOpenPreviewAndDoesNothingOtherwise() = runTest {
        withPlayer { player, sent, _ ->
            player.exit()
            runCurrent()
            assertTrue(sent.isEmpty())
            player.start()
            runCurrent()
            player.exit()
            runCurrent()
            assertEquals(1, sent.count { it.cmd == 0x03 && it.sub == 0x08 })
        }
    }

    @Test fun noPreviewWhileAWriteRuns() = runTest {
        withPlayer { player, sent, env ->
            assertTrue(env.guard.tryAcquire(LinkGuard.Use.WRITE))
            player.start()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(player.state.value.busy)
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun levelsBetweenStepsAreLinear() {
        assertEquals(Aquarium.STEPS[20], PreviewPlayer.levelsAt(Aquarium.STEPS, 600))
        // 09:45, halfway from step 19 (40,20,60,40) to step 20 (100,40,80,80).
        assertEquals(listOf(70, 30, 70, 60, 0), PreviewPlayer.levelsAt(Aquarium.STEPS, 585))
        // 23:45 loops to 00:00.
        assertEquals(listOf(0, 0, 0, 0, 0), PreviewPlayer.levelsAt(Aquarium.STEPS, 1425))
    }

    /** D23: a name the controller refuses (here 13 characters) stops the preview before `06 01`. */
    @Test fun aNameThatCannotBeSentOpensNoPreview() = runTest {
        withPlayer(name = "court_basique") { player, sent, env ->
            player.start()
            runCurrent()
            advanceTimeBy(3_000)
            runCurrent()
            assertTrue(player.state.value.nameRefused)
            assertFalse(player.state.value.open)
            assertTrue(sent.isEmpty())
            assertEquals(0, env.sim.received.size)
            // The link stays free: a write can start.
            assertTrue(env.guard.tryAcquire(LinkGuard.Use.WRITE))
        }
    }
}
