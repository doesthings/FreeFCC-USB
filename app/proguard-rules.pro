# Keep DUMPL frame builder
-keep class com.freefcc.n1.DumplBuilder { *; }
-keep class com.freefcc.n1.DumplFrame { *; }

# Keep usb-serial-for-android CDC ACM driver
-keep class com.hoho.android.usbserial.driver.CdcAcmSerialDriver { *; }
-keep class com.hoho.android.usbserial.driver.UsbSerialPort { *; }