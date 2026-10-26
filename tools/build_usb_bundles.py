#!/usr/bin/env python3
"""Build the NEMESIS package and USB install/update/uninstall bundles; never installs or connects to a car."""
import argparse
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_ID = "tilepad"
ACTIONS = ("install", "update", "uninstall")

def pack(source, destination):
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(source).as_posix())

def require_signed(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
    if "classes.dex" not in names:
        raise SystemExit(f"{apk} has no classes.dex")
    if not any(n.startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC")) for n in names):
        raise SystemExit(f"{apk} is not signed; build it with tools/build_apk.sh (it goes into /system/app)")

def copy_lf(source, destination):
    # Car-side scripts run under KitKat mksh; never ship CRLF.
    destination.write_bytes(source.read_bytes().replace(b"\r\n", b"\n"))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "dist/Tilepad.apk",
                        help="Signed Tilepad APK (default: dist/Tilepad.apk from tools/build_apk.sh)")
    args = parser.parse_args()
    apk = args.apk.resolve()
    if not apk.is_file():
        raise SystemExit(f"Missing {apk}. Run tools/build_apk.sh first, or pass --apk.")
    require_signed(apk)

    # Work only in this repository's generated build directory.
    stage = ROOT / "build/usb-bundles"
    if stage.exists():
        shutil.rmtree(stage)
    package = stage / "package"
    package.mkdir(parents=True)
    for path in sorted((ROOT / "tune-service").rglob("*")):
        if path.is_file():
            target = package / path.relative_to(ROOT / "tune-service")
            target.parent.mkdir(parents=True, exist_ok=True)
            copy_lf(path, target)
    (package / "app").mkdir()
    shutil.copy2(apk, package / "app/Tilepad.apk")

    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    package_zip = dist / f"{PACKAGE_ID}.zip"
    pack(package, package_zip)

    for action in ACTIONS:
        usb = stage / action
        scripts = usb / "tilepad"
        scripts.mkdir(parents=True)
        copy_lf(ROOT / "tune-service/scripts/common.sh", scripts / "common.sh")
        copy_lf(ROOT / "usb/tilepad/lib.sh", scripts / "lib.sh")
        copy_lf(ROOT / f"usb/tilepad/{action}.sh", scripts / f"{action}.sh")
        if action != "uninstall":
            (usb / "packages").mkdir()
            shutil.copy2(package_zip, usb / f"packages/{PACKAGE_ID}.zip")
        (usb / "NEMESIS.data").write_bytes(
            f"[nemesis]\nversion=1.0\ntype=execute\npath=tilepad/{action}.sh\n".encode())
        out = dist / f"Tilepad-{action}-usb.zip"
        pack(usb, out)
        print(out)
    print(package_zip)

if __name__ == "__main__":
    main()
