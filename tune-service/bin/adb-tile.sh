#!/system/bin/sh
# The ADB tile's root-side control, run by Tilepad through `nemesis root -c`. It only flips the
# relay daemon's on flag (or files an off request) and reports its status; the daemon (AdbGate,
# svc/adbgated.sh) polls for them and does the actual work (moving or stopping adbd, capturing
# keys). Off is a request rather than just the missing flag so that the daemon stops adbd only
# when asked, never because it started up without the flag.
#
#   sh /data/nemesis/apps/tilepad/bin/adb-tile.sh on|off|status
#
# status prints the daemon's status file (key=value lines Tilepad parses), or state=off before
# the daemon has ever run.
. /data/nemesis/apps/tilepad/scripts/common.sh

FLAG=$ADB_SNAP/relay.on
OFF=$ADB_SNAP/relay.off
STATUS=$ADB_SNAP/status

case "$1" in
    on) mkdir -p "$ADB_SNAP" && rm -f "$OFF" && : > "$FLAG" && echo "state=starting" ;;
    off) rm -f "$FLAG"; mkdir -p "$ADB_SNAP" && : > "$OFF" && echo "state=stopping" ;;
    status) if [ -f "$STATUS" ]; then cat "$STATUS"; else echo "state=off"; fi ;;
    *) echo "state=error"; echo "detail=usage: adb-tile.sh on|off|status"; exit 1 ;;
esac
exit 0
