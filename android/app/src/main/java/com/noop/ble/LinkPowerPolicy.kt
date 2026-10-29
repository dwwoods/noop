package com.noop.ble

/**
 * Strap-battery policy for the always-on WHOOP link (pure, unit-tested in LinkPowerPolicyTest).
 *
 * NOOP holds the GATT link open 24/7 so offloads, alarms and haptics just work. The cost of that
 * link on the STRAP is set by how often its radio has to wake: the connection interval, plus every
 * command/descriptor write we send. This policy only spends radio time when someone is using it:
 *  - an offload in flight gets HIGH priority (short interval, fast transfer, then it ends sooner);
 *  - a live HR consumer (Live screen, Health Monitor, Continuous HRV) gets BALANCED;
 *  - everything else idles at LOW_POWER, with slow battery polls and no stall bounces.
 */
object LinkPowerPolicy {

    /** Mirrors BluetoothGatt.CONNECTION_PRIORITY_* without pulling android.* into the pure policy. */
    enum class Priority { HIGH, BALANCED, LOW_POWER }

    /** Keep-alive tick cadence the client uses (30s). The tick counts below are in these units. */
    const val TICK_MS = 30_000L

    /** Battery poll every 2 ticks (~60s) while live HR is wanted; every 20 ticks (~10 min) when idle. */
    const val BATTERY_POLL_TICKS_LIVE = 2
    const val BATTERY_POLL_TICKS_IDLE = 20

    /** Stall-bounce fuses. Live: the existing tight 120s (a frozen live HR is the bug it fixes). A known
     *  history-empty 5/MG: the existing 10 min (#580). Idle: 20 min, longer than both the idle battery
     *  poll and the 15 min periodic offload, so a healthy quiet link is never torn down and rebuilt. */
    const val STALL_LIVE_MS = 120_000L
    const val STALL_5MG_EMPTY_MS = 600_000L
    const val STALL_IDLE_MS = 1_200_000L

    /** Stream quiet this long ⇒ re-subscribe once in case a CCCD silently dropped (live only). */
    const val QUIET_RESUBSCRIBE_MS = 45_000L

    fun priority(backfilling: Boolean, wantsRealtime: Boolean): Priority = when {
        backfilling -> Priority.HIGH
        wantsRealtime -> Priority.BALANCED
        else -> Priority.LOW_POWER
    }

    fun bounceFuseMs(wantsRealtime: Boolean, whoop5HistoryEmpty: Boolean): Long = when {
        !wantsRealtime -> STALL_IDLE_MS
        whoop5HistoryEmpty -> STALL_5MG_EMPTY_MS
        else -> STALL_LIVE_MS
    }

    /** Only a live consumer needs the quiet-stream re-subscribe. With no live stream armed, silence
     *  between battery polls is normal, and re-writing every CCCD each minute just wakes the strap. */
    fun shouldResubscribe(silentMs: Long, resubscribedSinceData: Boolean, wantsRealtime: Boolean): Boolean =
        wantsRealtime && !resubscribedSinceData && silentMs > QUIET_RESUBSCRIBE_MS

    fun batteryPollEveryTicks(wantsRealtime: Boolean): Int =
        if (wantsRealtime) BATTERY_POLL_TICKS_LIVE else BATTERY_POLL_TICKS_IDLE
}
