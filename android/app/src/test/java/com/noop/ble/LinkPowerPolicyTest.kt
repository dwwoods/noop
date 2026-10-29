package com.noop.ble

import com.noop.ble.LinkPowerPolicy.BATTERY_POLL_TICKS_IDLE
import com.noop.ble.LinkPowerPolicy.Priority
import com.noop.ble.LinkPowerPolicy.STALL_5MG_EMPTY_MS
import com.noop.ble.LinkPowerPolicy.STALL_IDLE_MS
import com.noop.ble.LinkPowerPolicy.STALL_LIVE_MS
import com.noop.ble.LinkPowerPolicy.TICK_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the always-on WHOOP link power policy ([LinkPowerPolicy]): connection priority per state,
 * stall-bounce fuse selection (including the idle fuse outlasting the periodic offload and battery
 * poll so a quiet link is never torn down), the quiet-stream re-subscribe gate, and battery poll
 * cadence. All pure, so no GATT stack is needed.
 */
class LinkPowerPolicyTest {

    @Test
    fun priority_isHigh_whileBackfilling_evenWhenRealtimeWanted() {
        // An offload in flight always wins: short interval so the transfer finishes sooner.
        assertEquals(Priority.HIGH, LinkPowerPolicy.priority(backfilling = true, wantsRealtime = true))
    }

    @Test
    fun priority_isBalanced_whenNotBackfilling_andRealtimeWanted() {
        // A live HR consumer gets a middle interval, not the fast one reserved for offloads.
        assertEquals(Priority.BALANCED, LinkPowerPolicy.priority(backfilling = false, wantsRealtime = true))
    }

    @Test
    fun priority_isLowPower_whenNotBackfilling_andRealtimeNotWanted() {
        // Nothing is using the link, so it idles at the slowest interval.
        assertEquals(Priority.LOW_POWER, LinkPowerPolicy.priority(backfilling = false, wantsRealtime = false))
    }

    @Test
    fun bounceFuse_isIdleFuse_whenRealtimeNotWanted() {
        // Without a live consumer the idle fuse applies regardless of the 5/MG history flag.
        assertEquals(STALL_IDLE_MS, LinkPowerPolicy.bounceFuseMs(wantsRealtime = false, whoop5HistoryEmpty = true))
        assertEquals(STALL_IDLE_MS, LinkPowerPolicy.bounceFuseMs(wantsRealtime = false, whoop5HistoryEmpty = false))
    }

    @Test
    fun bounceFuse_isLiveFuse_whenRealtimeWanted_andHistoryNotEmpty() {
        // A live stream with history present keeps the tight 120s fuse.
        assertEquals(STALL_LIVE_MS, LinkPowerPolicy.bounceFuseMs(wantsRealtime = true, whoop5HistoryEmpty = false))
    }

    @Test
    fun bounceFuse_is5MgEmptyFuse_whenRealtimeWanted_andHistoryEmpty() {
        // A known history-empty 5/MG gets the longer 10 min fuse (#580).
        assertEquals(STALL_5MG_EMPTY_MS, LinkPowerPolicy.bounceFuseMs(wantsRealtime = true, whoop5HistoryEmpty = true))
    }

    @Test
    fun idleFuse_outlastsPeriodicOffloadAndIdleBatteryPoll() {
        // The idle fuse must exceed the 15 min periodic offload and the idle battery poll period,
        // so a healthy quiet link is never bounced by its own scheduled traffic.
        assertTrue(STALL_IDLE_MS > 900_000L)
        assertTrue(STALL_IDLE_MS > BATTERY_POLL_TICKS_IDLE * TICK_MS)
    }

    @Test
    fun shouldResubscribe_isFalse_whenRealtimeNotWanted_evenAfterTenMinutesSilence() {
        // Silence with no live stream armed is normal; re-writing CCCDs would only wake the strap.
        assertFalse(
            LinkPowerPolicy.shouldResubscribe(
                silentMs = 600_000L,
                resubscribedSinceData = false,
                wantsRealtime = false,
            )
        )
    }

    @Test
    fun shouldResubscribe_isTrue_whenWanted_notYetResubscribed_andSilent46s() {
        // Live stream quiet past the 45s threshold, with no re-subscribe yet: re-arm once.
        assertTrue(
            LinkPowerPolicy.shouldResubscribe(
                silentMs = 46_000L,
                resubscribedSinceData = false,
                wantsRealtime = true,
            )
        )
    }

    @Test
    fun shouldResubscribe_isFalse_whenAlreadyResubscribed() {
        // Only one re-subscribe per quiet stretch; a second one is suppressed.
        assertFalse(
            LinkPowerPolicy.shouldResubscribe(
                silentMs = 46_000L,
                resubscribedSinceData = true,
                wantsRealtime = true,
            )
        )
    }

    @Test
    fun shouldResubscribe_isFalse_atExactly45s_strictGreaterThan() {
        // Boundary: the threshold is strict, so exactly 45s of silence does not trigger.
        assertFalse(
            LinkPowerPolicy.shouldResubscribe(
                silentMs = 45_000L,
                resubscribedSinceData = false,
                wantsRealtime = true,
            )
        )
    }

    @Test
    fun batteryPollEveryTicks_is2_whenLive_andIs20_whenIdle() {
        // About 60s between battery polls while live HR is wanted, about 10 min when idle.
        assertEquals(2, LinkPowerPolicy.batteryPollEveryTicks(wantsRealtime = true))
        assertEquals(20, LinkPowerPolicy.batteryPollEveryTicks(wantsRealtime = false))
    }
}
