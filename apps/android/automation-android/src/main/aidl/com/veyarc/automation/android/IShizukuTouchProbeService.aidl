package com.veyarc.automation.android;

import com.veyarc.automation.android.IShizukuTouchRecordingCallback;

interface IShizukuTouchProbeService {
    void destroy() = 16777114;
    void exit() = 1;
    void startObservation(long durationMillis) = 2;
    void stopObservation() = 3;
    boolean isRunning() = 4;
    long getEventCount() = 5;
    long getTouchContactCount() = 6;
    long getPositionSampleCount() = 7;
    int getProcessUid() = 8;
    String getLastError() = 9;
    String getDeviceSummary() = 10;
    long getFrameCount() = 11;
    long getCompleteGestureCount() = 12;
    long getMalformedFrameCount() = 13;
    long getIncompleteGestureCount() = 14;
    long getOutOfRangeSampleCount() = 15;
    void startRecording(long durationMillis, IShizukuTouchRecordingCallback callback) = 16;
}
