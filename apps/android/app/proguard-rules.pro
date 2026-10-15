# Shizuku constructs these Binder services reflectively outside the application process.
-keep,allowoptimization class com.veyarc.automation.android.ShizukuInputReplayUserService {
    public <init>();
}
-keep,allowoptimization class com.veyarc.automation.android.ShizukuTouchProbeUserService {
    public <init>();
}
