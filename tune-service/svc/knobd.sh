#!/system/bin/sh
# NEMESIS service (mode=restart): open the configured app when the TUNE knob is held.
# Runs the knob daemon via app_process using the bundled APK purely as a dex container
# (no PackageManager registration involved). svcmgr tracks the daemon's own pid.
. /data/nemesis/apps/tilepad/scripts/common.sh
DEVICE_NAME=daudio_wp_daudioplus_neev23_us
KEY_CODE=17
HOLD_MS=800
TARGET_COMPONENT=com.tilepad/.MainActivity
[ -r "$HERE/knob.conf" ] && . "$HERE/knob.conf"

# app_process needs init's framework environment (BOOTCLASSPATH); load it if svcmgr
# did not pass it through.
if [ -z "$BOOTCLASSPATH" ] && [ -r /init.environ.rc ]; then
    while read CMD NAME VALUE; do
        [ "$CMD" = export ] && export "$NAME=$VALUE"
    done < /init.environ.rc
fi

# The target is a normal framework app, so wait until Android is up before listening.
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done

log "exec daemon -> $TARGET_COMPONENT"
export CLASSPATH="$DEX"
exec app_process /system/bin --nice-name=tilepad-knobd com.tilepad.knob.KnobDaemon \
    "$DEVICE_NAME" "$KEY_CODE" "$HOLD_MS" "$TARGET_COMPONENT"
