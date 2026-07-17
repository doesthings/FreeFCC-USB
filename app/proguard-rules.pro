# Keep DUMPL frame builder
-keep class com.freefcc.n1.DumplBuilder { *; }
-keep class com.freefcc.n1.DumplFrame { *; }

# usb-serial-for-android: the prober table and driver classes are looked up
# reflectively / via class-name tables; keep them so R8 doesn't strip them.
-keep class com.hoho.android.usbserial.** { *; }