package cn.ianzb.hyperrefine.hook.volume

import org.junit.Assert.*
import org.junit.Test

class AppVolumeDeliveryTest {
    @Test fun successfulDirectWriteUpdatesEverySharedUidPlayerOnceAndStillMirrors() {
        val players = listOf(
            AppVolumePolicy.Player("track-1", 10_276, 1, 2, 1, null),
            AppVolumePolicy.Player("track-2", 10_276, 1, 2, 1, null),
            AppVolumePolicy.Player("paused", 10_276, 1, 3, 1, null),
            AppVolumePolicy.Player("other", 10_301, 2, 2, 1, null),
        )
        val writes = mutableListOf<Pair<String, Float>>()
        val mirrors = mutableListOf<Float>()
        val write = { v: Float -> AppVolumePolicy.applyVolume(players, 10_276, v, { id, value -> writes.add(id to value) }) }
        AppVolumeDelivery.send(0.23f, write) { value, applied ->
            assertTrue(applied)
            AppVolumeDelivery.receive(value, applied, write, { mirrors.add(it) })
        }
        assertEquals(listOf("track-1" to 0.23f, "track-2" to 0.23f), writes)
        assertEquals(listOf(0.23f), mirrors)
    }

    @Test fun failedDirectWriteUsesTheOriginalReceiverFallback() {
        val values = mutableListOf<Float>()
        AppVolumeDelivery.send(0.7f, { 0 }) { value, applied ->
            assertFalse(applied)
            AppVolumeDelivery.receive(value, applied, { values.add(it); 1 }, { values.add(it) })
        }
        assertEquals(listOf(0.7f, 0.7f), values)
    }

    @Test fun unavailableDirectApiStillForwardsAndLegacyBroadcastStillWrites() {
        var writes = 0
        var mirrored = false
        AppVolumeDelivery.send(0.6f, { error("API unavailable") }) { value, applied ->
            assertFalse(applied)
            AppVolumeDelivery.receive(value, false, { writes++; 2 }, { mirrored = true })
        }
        assertEquals(1, writes)
        assertTrue(mirrored)
    }

    @Test fun unsuccessfulLegacyWriteDoesNotPublishAnUnappliedMirror() {
        AppVolumeDelivery.receive(0.5f, false, { 0 }, { fail("nothing was written") })
    }

    @Test fun invalidValuesNeverWriteOrForwardAndValidValuesStayBounded() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            AppVolumeDelivery.send(value, { fail("invalid write"); 0 }, { _, _ -> fail("invalid forward") })
            AppVolumeDelivery.receive(value, true, { fail("invalid write"); 0 }, { fail("invalid mirror") })
        }
        val forwarded = mutableListOf<Float>()
        listOf(-1f, 0f, 1f, 2f).forEach { value ->
            AppVolumeDelivery.send(value, { 1 }) { v, _ -> forwarded.add(v) }
        }
        assertEquals(listOf(0f, 0f, 1f, 1f), forwarded)
    }
}
