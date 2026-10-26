#!/system/bin/sh
# NEMESIS postinstall (root, cwd = package dir). Places the bundled Tilepad APK at
# /system/app/Tilepad.apk -- the only /system file this package touches. All
# staging, verification and rollback copies live in $WORK (/data/local/tmp).
# svcmgr starts the knobd service after this returns.
. /data/nemesis/apps/tilepad/scripts/common.sh

fail() {
    log "postinstall: FAILED: $*"
    rm -rf "$WORK"
    exit 1
}

[ -f "$DEX" ] || fail "missing $DEX"

if [ -f "$SYSAPK" ] && same_file "$DEX" "$SYSAPK"; then
    log "postinstall: $SYSAPK already current; /system not touched"
    exit 0
fi

# Prepare and verify everything in tmp first.
rm -rf "$WORK"
mkdir -p "$WORK" || fail "cannot create $WORK"
cat "$DEX" > "$WORK/Tilepad.apk" && same_file "$DEX" "$WORK/Tilepad.apk" \
    || fail "cannot stage APK in $WORK"
HAD_PREVIOUS=0
if [ -f "$SYSAPK" ]; then
    cat "$SYSAPK" > "$WORK/Tilepad-previous.apk" && same_file "$SYSAPK" "$WORK/Tilepad-previous.apk" \
        || fail "cannot back up current $SYSAPK to $WORK"
    HAD_PREVIOUS=1
fi

NEW_SIZE=$(file_size "$WORK/Tilepad.apk")
OLD_SIZE=0
[ "$HAD_PREVIOUS" = 1 ] && OLD_SIZE=$(file_size "$SYSAPK")
FREE=$(system_free)
if [ -n "$FREE" ]; then
    # Overwriting truncates the old file first, so its blocks count as free. Keep 1 MiB spare.
    if [ $(( FREE + OLD_SIZE )) -lt $(( NEW_SIZE + 1048576 )) ]; then
        fail "not enough space on /system (free=$FREE old=$OLD_SIZE new=$NEW_SIZE); nothing changed"
    fi
else
    log "postinstall: WARN: could not read free space on /system; relying on verify/rollback"
fi

# The single /system write.
stop_app
system_rw || fail "nemesis remount rw failed; nothing changed"
if cat "$WORK/Tilepad.apk" > "$SYSAPK" && chown 0:0 "$SYSAPK" && chmod 0644 "$SYSAPK" \
    && sync && same_file "$WORK/Tilepad.apk" "$SYSAPK"; then
    system_restore
    rm -rf "$WORK"
    log "postinstall: installed $SYSAPK ($NEW_SIZE bytes)"
    exit 0
fi

log "postinstall: write to $SYSAPK failed or did not verify; rolling back"
if [ "$HAD_PREVIOUS" = 1 ]; then
    if cat "$WORK/Tilepad-previous.apk" > "$SYSAPK" && chown 0:0 "$SYSAPK" && chmod 0644 "$SYSAPK" \
        && sync && same_file "$WORK/Tilepad-previous.apk" "$SYSAPK"; then
        log "postinstall: previous $SYSAPK restored"
    else
        # Keep the backup for manual recovery; it survives until reboot.
        system_restore
        log "postinstall: FAILED: could not restore previous APK; backup kept at $WORK/Tilepad-previous.apk (cleared on reboot)"
        exit 1
    fi
else
    rm -f "$SYSAPK"
    sync
fi
system_restore
fail "APK not installed"
