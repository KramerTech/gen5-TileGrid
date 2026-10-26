#!/usr/bin/env python3
"""
Prepare the APK for the project's existing Android 4.4 v1 signing pipeline.

Remove the previous signature/build metadata and directory entries before signing
the payload again. This preserves the previously verified packaging process; it
is not a bypass for Hyundai's PackageManager certificate restrictions.
"""
import sys
import zipfile

src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for info in zin.infolist():
        name = info.filename
        if name.endswith("/"):
            continue                      # directory entry -> unsigned under v1
        if name.startswith("META-INF/"):
            continue                      # old signature + AGP build metadata
        data = zin.read(name)
        # resources.arsc must stay STORED and 4-byte aligned for mmap on-device.
        method = zipfile.ZIP_STORED if name == "resources.arsc" else zipfile.ZIP_DEFLATED
        out = zipfile.ZipInfo(name)
        out.compress_type = method
        out.external_attr = 0o644 << 16
        zout.writestr(out, data)
print(dst)
