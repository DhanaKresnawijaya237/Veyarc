package com.veyarc.automation.android;

/**
 * Bounded Shizuku shell-identity replay surface.
 *
 * This interface exposes fixed tap/swipe operations and a validated single-pointer motion trace.
 * It does not accept shell command strings, raw device writes, or arbitrary workflow payloads.
 */
interface IShizukuInputReplayService {
    void destroy() = 16777114;
    void exit() = 1;
    int getProcessUid() = 2;
    boolean isRunning() = 3;
    int getQueuedCount() = 4;
    int getCompletedCount() = 5;
    String getLastCommand() = 6;
    String getLastError() = 7;
    void clearQueue() = 8;
    void enqueueTap(float x, float y) = 9;
    void enqueueSwipe(float startX, float startY, float endX, float endY, long durationMillis) = 10;
    void startQueue() = 11;
    void cancel() = 12;
    int getCapabilities() = 13;
    void enqueueMotionTrace(in int[] actions, in float[] xs, in float[] ys, in long[] delaysMillis) = 14;
    void enqueueTargetedMotionTrace(
        in int[] actions,
        in float[] xs,
        in float[] ys,
        in long[] delaysMillis,
        int targetUid
    ) = 15;
}
