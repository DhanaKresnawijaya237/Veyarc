package com.veyarc.automation.android;

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
}
