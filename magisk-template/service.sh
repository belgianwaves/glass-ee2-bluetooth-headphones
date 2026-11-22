#!/system/bin/sh
setprop persist.service.bt.a2dp.sink true
setprop persist.service.bt.avrcp.controller true
setprop persist.service.bt.hfp.client false
setprop persist.sys.usb.config diag,adb

while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 1
done

pm enable com.android.bluetooth/.a2dpsink.A2dpSinkService >/dev/null 2>&1
pm enable com.android.bluetooth/.a2dpsink.mbs.A2dpMediaBrowserService >/dev/null 2>&1
pm enable com.android.bluetooth/.avrcpcontroller.AvrcpControllerService >/dev/null 2>&1
pm disable com.android.bluetooth/.a2dp.A2dpService >/dev/null 2>&1
pm disable com.android.bluetooth/.hfp.HeadsetService >/dev/null 2>&1

if ! dumpsys bluetooth_manager 2>/dev/null | grep -q "A2dpSinkService"; then
  # Restart Bluetooth cleanly via root service call (8=disable, 6=enable)
  service call bluetooth_manager 8 >/dev/null 2>&1
  sleep 2
  service call bluetooth_manager 6 >/dev/null 2>&1
fi
