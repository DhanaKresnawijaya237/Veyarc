package com.veyarc.automation.android;

oneway interface IShizukuTouchRecordingCallback {
    void onStarted(long startTimestampMicros, String deviceSummary, long minX, long maxX, long minY, long maxY) = 1;
    void onFrame(long timestampMicros, int action, long x, long y) = 2;
    void onError(String message) = 3;
    void onCompleted() = 4;
}
