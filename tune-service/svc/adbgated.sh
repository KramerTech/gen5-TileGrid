#!/system/bin/sh
# NEMESIS service (mode=restart): the ADB tile's "let every computer in" relay. Runs the
# AdbGate daemon via app_process, using the bundled APK as its dex container -- the same way
# svc/knobd.sh runs the knob daemon. The daemon stays idle until the tile creates the flag
# file ($ADB_SNAP/relay.on); it then fronts the ADB port and captures keys. See AdbGate.java.
. /data/nemesis/apps/tilepad/scripts/common.sh

# The port computers connect to (and that the Wi-Fi tile advertises); the internal port adbd
# is moved to while the relay owns the public one.
PUBLIC_PORT=5555
ADBD_PORT=5565
STATE_DIR=$ADB_SNAP
KEYS_HELPER=$HERE/bin/adb-keys.sh

# app_process needs init's framework environment (BOOTCLASSPATH); load it if svcmgr did not
# pass it through.
if [ -z "$BOOTCLASSPATH" ] && [ -r /init.environ.rc ]; then
    while read CMD NAME VALUE; do
        [ "$CMD" = export ] && export "$NAME=$VALUE"
    done < /init.environ.rc
fi

# adbd control uses getprop/setprop and the framework; wait until Android is up.
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done

mkdir -p "$STATE_DIR"
log "exec adbgate daemon (public=$PUBLIC_PORT adbd=$ADBD_PORT)"
export CLASSPATH="$DEX"
exec app_process /system/bin --nice-name=tilepad-adbgate com.tilepad.adb.AdbGate \
    "$PUBLIC_PORT" "$ADBD_PORT" "$STATE_DIR" "$NEMESIS" "$KEYS_HELPER"
