# Keep DUMPL frame builder (reflection-free but referenced from JNI-like dispatch)
-keep class com.freefcc.n1.DumplBuilder { *; }
-keep class com.freefcc.n1.DumplFrame { *; }

# Keep profiles JSON model
-keep class com.freefcc.n1.ProfileLoader$Profile { *; }
-keep class com.freefcc.n1.ProfileLoader$Frame { *; }