#!/system/bin/sh
# Manages /data/misc/adb/adb_keys for the ADB relay (AdbGate), run as uid 1000 through
# `nemesis root 1000 -c` (adb_keys is system:shell, writable only by uid 1000).
#
#   add FILE   append the public-key line in FILE to adb_keys and record it in a ledger
#   clear      remove from adb_keys exactly the keys the relay added, then drop the ledger
#
# adbd reads adb_keys as group shell (0640 system:shell). nemesis root has no CAP_CHOWN, so
# uid 1000 can't restore that ownership if the file were replaced. Every write therefore keeps
# the existing inode: `add` appends, and `clear` truncates in place (`: > file`) and rewrites.
. /data/nemesis/apps/tilepad/scripts/common.sh

KEYS=/data/misc/adb/adb_keys
LEDGER=/data/misc/adb/tilepad_added

# 0 if FILE has a line exactly equal to LINE. Uses its own variable name so it can be called
# from inside a loop over another "L" (the clear loop) without clobbering it.
has_line() {
    [ -f "$1" ] || return 1
    while IFS= read -r HL || [ -n "$HL" ]; do [ "$HL" = "$2" ] && return 0; done < "$1"
    return 1
}

case "$1" in
    add)
        [ -f "$2" ] || { echo "no key file $2"; exit 1; }
        IFS= read -r LINE < "$2"
        [ -n "$LINE" ] || { echo "empty key"; exit 1; }
        if [ ! -f "$KEYS" ]; then
            : > "$KEYS" || { echo "cannot create $KEYS"; exit 1; }
            chmod 640 "$KEYS" 2>/dev/null
        fi
        has_line "$KEYS" "$LINE" || print -r -- "$LINE" >> "$KEYS"
        has_line "$LEDGER" "$LINE" || print -r -- "$LINE" >> "$LEDGER"
        exit 0
        ;;
    clear)
        [ -f "$LEDGER" ] || exit 0
        if [ -f "$KEYS" ]; then
            # Keep adb_keys' inode (and its system:shell 0640): read it, truncate in place,
            # then write back every line the relay did not add.
            OLD=$(cat "$KEYS")
            : > "$KEYS"
            print -r -- "$OLD" | while IFS= read -r L || [ -n "$L" ]; do
                [ -z "$L" ] && continue
                has_line "$LEDGER" "$L" || print -r -- "$L" >> "$KEYS"
            done
        fi
        rm -f "$LEDGER"
        exit 0
        ;;
    *) echo "usage: adb-keys.sh add FILE | clear"; exit 1 ;;
esac
exit 0
