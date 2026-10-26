#!/system/bin/sh
# NEMESIS Manager > Packages > Launch (and `nemesis pkg-launch tilepad`):
# open the same component the TUNE hold opens.
. /data/nemesis/apps/tilepad/scripts/common.sh
TARGET_COMPONENT=com.tilepad/.MainActivity
[ -r "$HERE/knob.conf" ] && . "$HERE/knob.conf"
am start -n "$TARGET_COMPONENT" -f 0x10000000
