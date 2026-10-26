#!/system/bin/sh
# NEMESIS type=execute: remove the Tilepad package and /system/app/Tilepad.apk.
# Does not remove NEMESIS itself.
. "${0%/*}/lib.sh"

# A stale stage dir from an interrupted install would make preuninstall think this
# is an update and keep the APK. It lives in tmp, so clearing it is safe.
rm -rf "$STAGE"

if pkg_installed; then
    run "$NEMESIS" uninstall "$PKG_ID" || finish 1
else
    say "$PKG_ID not installed as a NEMESIS package"
fi

# Covers a manual (README/ADB) install or a failed hook: remove only our APK.
if [ -f "$SYSAPK" ]; then
    say "removing leftover $SYSAPK"
    stop_app
    system_rw || { say "FAIL: nemesis remount rw failed"; finish 1; }
    rm -f "$SYSAPK"
    sync
    system_restore
fi
rm -f "$DEXCACHE"

OK=0
if pkg_installed; then say "FAIL: $PKG_ID still registered"; OK=1; else say "ok: $PKG_ID not registered"; fi
if [ -f "$SYSAPK" ]; then say "FAIL: $SYSAPK still present"; OK=1; else say "ok: $SYSAPK removed"; fi
finish $OK
