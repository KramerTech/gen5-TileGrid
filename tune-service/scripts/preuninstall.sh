#!/system/bin/sh
# NEMESIS preuninstall (root; services already stopped). Also runs on the OLD
# package during `nemesis install --update`, after the new package has been
# extracted to $STAGE.
. /data/nemesis/apps/tilepad/scripts/common.sh

log "preuninstall: removing daemon dalvik cache"
rm -f "$DEXCACHE"

if [ -f "$STAGE/NEMESIS.data" ]; then
    # Update: leave /system alone. The new postinstall rewrites the APK in place,
    # so Android keeps the app's data (favorites, settings).
    log "preuninstall: update in progress; leaving $SYSAPK for the new postinstall"
    exit 0
fi

# ADB tile: a real uninstall turns ADB off if the tile had it on -- remove exactly the keys the
# relay added and stop adbd, as the tile's own off does (AdbGate.turnOff). The services are
# already stopped, so do it here directly. ADB that something else turned on is left alone.
# (An update returned above, so the relay stays on across updates.)
if [ -f /data/misc/adb/tilepad_added ]; then
    "$NEMESIS" root 1000 -c "sh $HERE/bin/adb-keys.sh clear" >/dev/null 2>&1
fi
if [ -f "$ADB_SNAP/relay.on" ]; then
    log "preuninstall: turning ADB off (the ADB tile had it on)"
    setprop persist.service.adb.tcp.enable 0
    setprop service.adb.tcp.port -1
    setprop ctl.stop adbd
    # The relay's firewall chain (AdbGate.firewallOn); gone at the next boot anyway.
    while iptables -D INPUT -j tilepad_adb 2>/dev/null; do :; done
    iptables -F tilepad_adb 2>/dev/null
    iptables -X tilepad_adb 2>/dev/null
fi
# ...and turn the Wi-Fi client off if ADB turned it on (svc needs the framework environment).
if [ -f "$ADB_SNAP/wifi.adb" ]; then
    if [ -z "$BOOTCLASSPATH" ] && [ -r /init.environ.rc ]; then
        while read CMD NAME VALUE; do
            [ "$CMD" = export ] && export "$NAME=$VALUE"
        done < /init.environ.rc
    fi
    log "preuninstall: turning the Wi-Fi client off (ADB had turned it on)"
    svc wifi disable >/dev/null 2>&1
fi
rm -rf "$ADB_SNAP"

if [ -f /data/nemesis/backup/tilepad/wifi-client ]; then
    # Other apps may rely on the client by now, and HMG Leafia Plus may have adopted it.
    log "preuninstall: leaving the Wi-Fi client on (turned on from the Wi-Fi tile). To turn it off,"
    log "preuninstall: delete /system/etc/permissions/android.hardware.wifi.xml and reboot."
    rm -rf /data/nemesis/backup/tilepad
fi

[ -f "$SYSAPK" ] || { log "preuninstall: $SYSAPK not present"; exit 0; }
stop_app
if ! system_rw; then
    log "preuninstall: FAILED: nemesis remount rw failed; $SYSAPK left in place"
    exit 1
fi
rm -f "$SYSAPK"
sync
system_restore
if [ -f "$SYSAPK" ]; then
    log "preuninstall: FAILED: could not remove $SYSAPK"
    exit 1
fi
log "preuninstall: removed $SYSAPK"
exit 0
