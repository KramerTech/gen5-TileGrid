# Tilepad

A minimal grid launcher for Hyundai's gen5W head unit (Android 4.4), installed through
[NEMESIS](https://github.com/xwtk/hmg_nemesis). It's a rebuilt UI on top of
[Launchpad](https://github.com/paytondevs/ioniq5-preconditioning)'s preconditioning backend and TUNE-hold service.
It's developed and tested on an IONIQ 5 (NEEV23 US).

Hold **TUNE** for about a second to open it. Hold again to close it.

## Features

- **Wi-Fi status** (if the head unit has a Wi-Fi module): network, IP, signal. Tap to pick a network.
  On a unit whose Wi-Fi only runs the CarPlay/Android Auto hotspot, tap to turn on the Wi-Fi client.
- **Manual battery preconditioning** (if available on the vehicle): one-tap start and stop.
- **ADB toggle**: turn ADB over Wi-Fi on and off. While it's on, any computer on the network can connect without approving a key, and Wi-Fi stays on (so wireless CarPlay/Android Auto are off).
- **NEMESIS Manager** shortcut.
- **Car Home**: back to Hyundai's home screen.
- **Reboot** the head unit, with confirmation.
- **Shortcuts** to any screen, including Hyundai and system screens that have no icon.
- **Rearrange, rename and remove** tiles with a long press.

"If available" comes from the car itself. Hyundai's firmware declares each car's options as
system features. The Wi-Fi tile needs `com.hkmc.software.wifi.module`, and the preconditioning
tile needs `com.hkmc.hardware.ev`. On a gas car or a unit without Wi-Fi, those tiles just don't
appear, and Tilepad never contacts the vehicle service. On an EV whose firmware differs from the
inspected IONIQ 5 build, the preconditioning tile still appears. It shows *Unavailable* and sends
nothing, because of Launchpad's firmware gate.

## Tiles

| Tile | Shows | Tap |
| --- | --- | --- |
| **Wi-Fi** *(Wi-Fi module only)* | signal bars, Connected / No network / Off, network name, IP, link speed. *Hotspot only* when the client is off | Android's Wi-Fi network picker, or *turn on Wi-Fi* (see below) |
| **Precondition** *(EVs only)* | Off / Waiting / Active / Unavailable…, spinner while waiting, elapsed time, a *Conditioning* pill while the car's own indicator is on | see below |
| **ADB** | On / Off, the port, and how many computers are connected; an *Allow all* pill while it's on, *Keys only* if something else turned ADB on | asks to confirm, then turns ADB over Wi-Fi on or off (see below) |
| **NEMESIS** | | NEMESIS Manager (dimmed if it's not installed) |
| **Car Home** | | closes to Hyundai's home screen |
| **Reboot** | | asks to confirm, then hard-reboots the head unit (`nemesis root -c reboot`) |
| **Add shortcut** | | searchable list of every screen (apps, plus Hyundai/system screens with no icon) |

**Preconditioning, one button:**
- Off → tap: sends Start and pops up a progress popup. It shows *Waiting for BMS* (usually 60–120 s) and turns into a ✓ *Preconditioning active* when the car reports it. If the popup was closed by then, it pops up on its own.
- Waiting → tap: reopens the popup, which has **Cancel request**. A stray tap won't cancel.
- Active → tap: turns it off. The popup shows *Turning off*, then *Off*.
- Anything else (Unavailable, Error, not READY…) → tap: a popup with the car's reason.

"Active" only ever comes from the car's own indicator, never from a timer. The backend is
Launchpad's, unchanged: firmware-hash gate, ownership journal, cancel-on-recovery, and a
foreground service that keeps the request alive after the app closes.

**Turning on the Wi-Fi client.** Stock US firmware, and wireless CarPlay/Android Auto mods
installed without their Wi-Fi client option (HMG Leafia Plus's `LEAFIA-WIFI-CLIENT`), use the
radio only for the projection hotspot. The tile then reads *Hotspot only*, and tapping it offers
to turn the client on. That does what Leafia's option does: it adds
`/system/etc/permissions/android.hardware.wifi.xml` (Hyundai's Wi-Fi service and settings app
read it as "client supported") and sets `persist.wifi.state=3` (client + wireless). It doesn't
write `build.prop`; the persistent property survives reboots on its own. The popup then offers a
reboot, since Android reads features at boot. Until then, the tile reads *Reboot to finish*.

- It's idempotent and never touches a file that's already there, so it can't fight Leafia. If
  Leafia is installed later with its client option, it writes the same file. If Leafia was
  installed first without it, Leafia's uninstall restores its snapshot and removes the client
  again, and the tile offers it again.
- There's one Wi-Fi radio. While it's on a network, wireless CarPlay/Android Auto may not connect.
- Uninstalling Tilepad leaves the client on (the uninstall log says so). To turn it off, delete
  that file and reboot.

**ADB over Wi-Fi.** The ADB tile turns ADB over Wi-Fi on and off. Tapping it when it's off asks
to confirm, because of what "on" means:

> ⚠️ While ADB is on, **any computer on the same Wi-Fi network can connect and get a
> root-capable shell**, with no key to approve on the head unit. Every computer's key is logged
> and saved, but nobody is turned away. Only turn it on on a network you trust, and turn it off
> when you're done.

> ⚠️ While ADB is on, **wireless CarPlay and Android Auto don't work.** The head unit has one
> Wi-Fi radio: it either joins a network or runs the hotspot your phone uses for wireless
> CarPlay, not both. ADB turns on Wi-Fi and keeps it on (turning the hotspot off), right away and
> again at every boot, until you turn ADB off.

- **Why a relay.** There's no "Allow USB debugging?" dialog on these head units. Turning the tile
  on starts a small relay (a NEMESIS service) that moves adbd to an internal port, listens on the
  normal one, and handles each computer's key before passing the connection to adbd. Connect
  with `adb connect <head-unit-ip>:5555`.
  - Some units' adbd doesn't check keys over TCP at all (despite `ro.adb.secure=1`). There the
    relay checks them itself, and every computer gets in on its first `adb connect`.
  - Where adbd does check keys, the relay adds a new computer's key to `adb_keys` and drops that
    first connection, so **a new computer's first `adb connect` fails once, and the next one
    works.**

  The head unit serves **one computer at a time**: while one is connected, another's
  `adb connect` hangs and shows `offline` or `unauthorized` until the first disconnects
  (`adb disconnect`).

  Either way, the relay logs each computer (address, key name and fingerprint) to the service
  log (`adbgated.log`) and saves each new key to `/data/nemesis/backup/tilepad/adb/keys`, which
  stays when you turn ADB off.
- **Wi-Fi only.** The relay refuses connections that don't arrive over Wi-Fi (the car's
  telematics modem has cellular links up too), and a firewall rule keeps everything but the
  relay off adbd's internal port.
- **Wi-Fi.** ADB needs the head unit on the same network as your computer, so while ADB is on it
  keeps Wi-Fi on and joined to your saved network, at every boot too. On a unit whose Wi-Fi only
  runs the CarPlay hotspot, turn on Wi-Fi from the Wi-Fi tile first.
- **Turning it off** turns ADB over Wi-Fi off completely: nothing listens for ADB until you turn it
  on again, after a reboot too. It also removes the keys the relay added, and turns Wi-Fi back off
  if ADB was what turned it on, so wireless CarPlay works again.
- If ADB over Wi-Fi was turned on some other way (another package, or a setting from before), the
  tile says *Keys only*: only computers approved before can connect. Tap it to turn ADB off, or to
  allow all.
- The tile shows how many computers (keys) have connected, and a spinner while it's starting or
  stopping.
- It needs NEMESIS (for root); without it the tile says so and does nothing.
- An **update** keeps ADB in whatever state you left it. **Uninstalling** Tilepad turns it off if
  the tile had it on (and turns off the Wi-Fi it turned on).

**Arranging:**
- Long-press any tile, then drag it. Tiles wiggle while you're in edit mode.
- In edit mode, shortcuts get ✎ (rename) on the top-left and − (remove) on the top-right. Core tiles can be moved but not removed or renamed.
- Leave edit mode with **Done**, by tapping empty space, or with Back.
- New shortcuts are added just before the Add tile. Renaming to an empty name restores the original name.

Search and rename use a built-in keyboard, so no Android IME is needed. Long-press ⌫ to clear.
The TUNE knob also works: turn it to move the highlight and press it to open the tile.

## Install (USB, no computer needed in the car)

1. **Uninstall Launchpad first** with its `Launchpad-uninstall-usb.zip`, then reboot. Both apps
   have a TUNE-hold service, so with both installed one hold opens both. Tilepad refuses to
   install (from USB or NEMESIS Manager) while any trace of Launchpad is left, and its log
   says what it found. Nothing is changed when it refuses.
2. Download **`Tilepad-install-usb.zip`** from the [latest release](https://github.com/KramerTech/gen5-TileGrid/releases/latest)
   and extract it so its *contents* sit at the root of a FAT32/exFAT drive, with nothing else
   from NEMESIS on it:
   ```text
   USB/
   ├── NEMESIS.data
   ├── packages/tilepad.zip      (don't unzip this)
   └── tilepad/{common,lib,install}.sh
   ```
   Or use `sh tools/usb/write-usb.sh install /media/$USER/YOUR_USB` (it moves old NEMESIS files
   into a backup folder instead of overwriting them).
3. In the car (parked, head unit fully booted), plug the drive into a USB data port and tap
   **Yes** on the NEMESIS prompt. Wait for the result dialog.
4. Reboot the head unit (pinhole button), remove the drive, and hold TUNE.

The installer writes `tilepad-install.log` to the drive. **Update** works the same way with
`Tilepad-update-usb.zip` and keeps your tiles. **Uninstall** uses `Tilepad-uninstall-usb.zip`.

What it changes on the car is the same as Launchpad, with different names. The package lives in
`/data/nemesis/apps/tilepad/`, NEMESIS runs the service `tilepad/knobd`, and the only file the
installer writes under `/system` is `/system/app/Tilepad.apk`. The one exception is turning on
the Wi-Fi client from the Wi-Fi tile, which you have to confirm (see above).

## Car test checklist

Things that can't be verified on an emulator, in rough priority order:

- [ ] Holding TUNE opens Tilepad, and holding it again closes it. If not, check NEMESIS Manager → Services → `tilepad/knobd`.
- [ ] Grid fit: expect 6 columns × 2 rows of ~185dp squares, clear of the split-screen handle on the right.
- [ ] Wi-Fi tile: name, IP and signal look right. Tapping opens the network picker.
- [ ] On a hotspot-only unit: the Wi-Fi tile reads *Hotspot only*. *Turn on* → *Reboot* → the tile shows the network state and opens the picker.
- [ ] Precondition: shows *Off · Tap to start* when READY with conditioning enabled in Hyundai's settings. Start → Waiting → Active (60–120 s), then tap → Off.
- [ ] NEMESIS tile opens Manager. Car Home returns to Hyundai's home.
- [ ] Add shortcut: the list loads, the keyboard filters it, and an added tile launches its screen.
- [ ] Reboot: confirm → the head unit restarts. If NEMESIS's root channel refuses, the popup shows its output.

If something fails, `adb logcat | grep -i tilepad` (with ADB enabled) or NEMESIS Manager →
Logs → Copy to USB helps.

## Build

```sh
./gradlew :app:testDebugUnitTest            # unit tests (tile order, tile support, grid math, presenter, search, backend)
sh tools/build_apk.sh                       # tests + lint + signed v1 APK → dist/Tilepad.apk
python3 tools/build_usb_bundles.py          # → dist/tilepad.zip + Tilepad-{install,update,uninstall}-usb.zip
```

`build_apk.sh` signs with `keystore/tilepad.jks`, whose password is in `keystore/password`. Both
are gitignored. **Back them up.** Future updates should be signed with the same key. If
`tools/signing-cert.sha256` exists, the build fails unless the APK's signing certificate has that
SHA-256 (`apksigner verify --print-certs dist/Tilepad.apk` prints it).

### Releases

GitHub Actions builds, signs and publishes releases. Bump `versionName` (and `versionCode`) in
`app/build.gradle.kts` and `version=` in `tune-service/NEMESIS.data`, commit, then push a matching tag:

```sh
git tag -a v1.0.1 -m "What changed"   # the message becomes the release notes
git push origin v1.0.1
```

The [release workflow](.github/workflows/release.yml) checks that the three versions match, runs
`build_apk.sh` (tests, lint, signing, certificate check) and `build_usb_bundles.py`, and publishes the
APK, `tilepad.zip`, the three USB bundles and `SHA256SUMS`. The job holding the key can only read the
repo; a separate job publishes. Every push and pull request runs the same build with a throwaway key.

The key comes from repository secrets:

```sh
base64 -i keystore/tilepad.jks | gh secret set TILEPAD_KEYSTORE_B64
gh secret set TILEPAD_STORE_PASSWORD < keystore/password
# only if the key's password differs from the store's: gh secret set TILEPAD_KEY_PASSWORD
```

The debug build simulates the vehicle on an emulator (`ranchu`/`goldfish`). Launch it with
`--ez design_preview true` to get a fake Wi-Fi connection for screenshots.

### Source map

| | |
| --- | --- |
| `MainActivity` | tiles, popups, preconditioning popup flow, Wi-Fi polling, TUNE knob |
| `TileGrid` | square layout, long-press drag reorder, edit mode, row-snapping scroll |
| `TileView` / `StatusTile` / `WifiTile` / `PreconTile` | tile views |
| `AddShortcutPage`, `KeyPad` | screen picker and built-in keyboard |
| `Sheet`, `StatusCard` | popup layer |
| `TileOrder`, `TileSpec`, `TileSupport`, `GridMath`, `PreconPresenter`, `SearchFilter` | pure, unit-tested logic (`TileSupport` decides which core tiles the car can back) |
| `Preconditioning*`, `OemVehicleClient`, `VehiclePort`, `knob/*` | Launchpad's backend, unchanged except for package names |
| `RootShell` | `nemesis root -c` (what NEMESIS Manager uses) |
| `tune-service/bin/wifi-client.sh` | root side of *turn on Wi-Fi* (run through `RootShell`) |

Preconditioning compatibility and safety notes are in Launchpad's
[COMPATIBILITY.md](https://github.com/paytondevs/ioniq5-preconditioning/blob/main/COMPATIBILITY.md). The firmware gate is unchanged.

Credits: [NEMESIS](https://github.com/xwtk/hmg_nemesis), Launchpad by paytondev,
[Ioniq5_CAN](https://github.com/tylerharvey/Ioniq5_CAN),
[gen5w-utils](https://github.com/Helloyunho/gen5w-utils).
