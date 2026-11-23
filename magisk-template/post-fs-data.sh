#!/system/bin/sh
# When vbmeta verification is disabled (--disable-verification) for Magisk on
# Glass EE2, Qualcomm's bootloader omits androidboot.avb_version, leaving
# ro.boot.avb_version unset ("0.0") while /system/compatibility_matrix.xml
# requires 1.0. That VINTF mismatch triggers the boot-time modal dialog
# "There's an internal problem with your device" which cannot be dismissed via
# the Glass touchpad alone. Restore ro.boot.avb_version=1.0 before zygote starts.
resetprop -n ro.boot.avb_version 1.0
resetprop -n ro.boot.vbmeta.avb_version 1.0
