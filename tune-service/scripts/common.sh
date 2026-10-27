# Shared by the hooks and the service. Toolbox only (no grep/awk/busybox on KitKat).
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
umask 022
HERE=/data/nemesis/apps/tilepad
NEMESIS=/system/xbin/nemesis
# The signed Tilepad APK. The knob daemon uses it as its dex container
# (CLASSPATH under app_process), and postinstall places a copy in /system/app so
# PackageManager registers the visible app.
DEX=$HERE/app/Tilepad.apk
# dalvik-cache entry app_process may create for that dex (named after its path).
DEXCACHE=/data/dalvik-cache/data@nemesis@apps@tilepad@app@Tilepad.apk@classes.dex
# The ONLY file under /system this package ever writes or removes.
SYSAPK=/system/app/Tilepad.apk
# Everything else (staging, verification copies, rollback backup) lives here.
WORK=/data/local/tmp/tilepad
# NEMESIS extracts an incoming package here before running the old package's
# uninstall hooks, so its presence during preuninstall means "update".
STAGE=/data/local/tmp/nemesis/stage/tilepad
APP_PACKAGE=com.tilepad
# State dir for the ADB relay (svc/adbgated.sh, bin/adb-tile.sh): the on flag, the off request,
# the daemon's status file and its list of every key that has connected (keys). It lives outside the package dir, so the relay stays on across an
# update; a real uninstall turns it off. (The keys the relay adds, and its ledger of them,
# live in /data/misc/adb next to adb_keys, where uid 1000 can write them.)
ADB_SNAP=/data/nemesis/backup/tilepad/adb

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') tilepad $*"; }

android_up() { [ "$(getprop sys.boot_completed)" = 1 ]; }

# 0 if two files have identical contents. The head unit's toolbox cmp has no -s; it prints
# differences to stdout and exits 1 (2 on errors), so silence it and use the exit status.
same_file() { cmp "$1" "$2" >/dev/null 2>&1; }

# Size in bytes of a file, from toolbox `ls -l` (perms owner group size date time name).
file_size() {
    set -- $(ls -l "$1" 2>/dev/null)
    case "$4" in ''|*[!0-9]*) echo 0 ;; *) echo "$4" ;; esac
}

# Free bytes on /system from toolbox df ("Filesystem Size Used Free Blksize",
# sizes like 312.4M). Prints nothing if the output cannot be parsed.
system_free() {
    df /system 2>/dev/null | while read FS SIZE USED FREE BLK; do
        [ "$FS" = /system ] || continue
        case "$FREE" in
            *K) MUL=1024 ;; *M) MUL=1048576 ;; *G) MUL=1073741824 ;; *) continue ;;
        esac
        NUM=${FREE%?}
        INT=${NUM%%.*}
        FRAC=0
        [ "$NUM" != "$INT" ] && FRAC=${NUM#*.}
        FRAC=${FRAC%${FRAC#?}}
        case "$INT$FRAC" in ''|*[!0-9]*) continue ;; esac
        echo $(( INT * MUL + FRAC * MUL / 10 ))
    done
}

# 0 if the last /system entry in /proc/mounts is mounted read-only.
system_is_ro() {
    RO=1
    while read DEV MNT FS OPTS REST; do
        [ "$MNT" = /system ] || continue
        case "$OPTS" in ro|ro,*) RO=0 ;; *) RO=1 ;; esac
    done < /proc/mounts
    return $RO
}

# Make /system writable only if it is read-only now, and remember to put it back.
REMOUNTED=0
system_rw() {
    if system_is_ro; then
        "$NEMESIS" remount rw || return 1
        REMOUNTED=1
    fi
    return 0
}
system_restore() {
    if [ "$REMOUNTED" = 1 ]; then
        "$NEMESIS" remount ro || log "WARN: could not remount /system ro"
        REMOUNTED=0
    fi
}

# 0 if NEMESIS lists package id $1.
pkg_registered() {
    "$NEMESIS" pkg-list 2>/dev/null | {
        while IFS='|' read ID REST; do
            [ "$ID" = "$1" ] && exit 0
        done
        exit 1
    }
}

# The original Launchpad has its own TUNE-hold service, so Tilepad refuses to install next
# to it. Prints one line per trace still on the car; prints nothing if it is gone.
LAUNCHPAD_ID=paytondev.launchpad
launchpad_traces() {
    pkg_registered "$LAUNCHPAD_ID" && echo "NEMESIS package $LAUNCHPAD_ID"
    [ -d "/data/nemesis/apps/$LAUNCHPAD_ID" ] && echo "/data/nemesis/apps/$LAUNCHPAD_ID"
    [ -f /system/app/Launchpad.apk ] && echo /system/app/Launchpad.apk
    if android_up; then
        case "$(pm path com.paytondev.launchpad 2>/dev/null)" in
            package:*) echo "Android app com.paytondev.launchpad" ;;
        esac
    fi
    return 0
}

stop_app() {
    android_up && am force-stop "$APP_PACKAGE" >/dev/null 2>&1
    return 0
}
