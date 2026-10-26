#!/bin/sh
# Write a Tilepad USB bundle onto a FAT32/exFAT drive. Runs on your computer;
# does not connect to the car.
#   tools/usb/write-usb.sh install|update|uninstall /Volumes/USB [bundle-dir]
# bundle-dir holds Tilepad-<action>-usb.zip (default: ./dist, then the current dir).
set -eu
usage() { echo "usage: $0 install|update|uninstall <usb-drive-path> [bundle-dir]" >&2; exit 2; }
[ $# -ge 2 ] || usage
ACTION=$1
DRIVE=$2
case "$ACTION" in install|update|uninstall) ;; *) usage ;; esac
ZIP=
find_zip() { [ -z "$ZIP" ] && [ -f "$1/Tilepad-$ACTION-usb.zip" ] && ZIP=$1/Tilepad-$ACTION-usb.zip; return 0; }
[ $# -ge 3 ] && find_zip "$3"
find_zip "$(dirname "$0")/../../dist"
find_zip dist
find_zip .
[ -n "$ZIP" ] || { echo "Tilepad-$ACTION-usb.zip not found; pass the folder you downloaded it to." >&2; exit 1; }
[ -d "$DRIVE" ] && [ -w "$DRIVE" ] || { echo "$DRIVE is not a writable directory (is the drive mounted?)" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }

# Move (never delete) anything from a previous NEMESIS job out of the drive root.
BACKUP=$DRIVE/NEMESIS-backup-$(date +%Y%m%d-%H%M%S)
for ITEM in NEMESIS.data NEMESIS.sh tilepad packages; do
    if [ -e "$DRIVE/$ITEM" ]; then
        mkdir -p "$BACKUP"
        mv "$DRIVE/$ITEM" "$BACKUP/"
        echo "moved $ITEM -> $BACKUP/"
    fi
done

unzip -q "$ZIP" -d "$DRIVE"
sync
echo "Wrote $(basename "$ZIP") to $DRIVE:"
(cd "$DRIVE" && ls -1d NEMESIS.data tilepad packages 2>/dev/null) || true
cat <<EOF

Next:
  1. Eject the drive, insert it into the car's USB data port (car on, parked).
  2. Tap Yes on the NEMESIS prompt and wait for the result dialog.
  3. Reboot the head unit (pinhole button), then remove the drive.
  The log is written to tilepad-$ACTION.log on the drive.
EOF
