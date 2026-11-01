#!/usr/bin/env python3
"""Copies the screenshots (PNG and JPEG) of an exported xcresult attachment folder to readable names.

Usage: export-captures.py <exported folder> <output folder> <prefix>
`xcrun xcresulttool export attachments` writes the files with generated names and a manifest.json.
"""
import json
import pathlib
import re
import shutil
import sys

source, target, prefix = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
target.mkdir(parents=True, exist_ok=True)
manifest = json.loads((source / "manifest.json").read_text())
count = 0
for test in manifest:
    for attachment in test.get("attachments", []):
        name = attachment.get("suggestedHumanReadableName", attachment["exportedFileName"])
        # Only the screenshots go to the report. XCTest also attaches debug text and recordings on a failure; the
        # result bundle keeps them.
        if not name.lower().endswith((".png", ".jpg", ".jpeg")):
            continue
        name = re.sub(r"_\d+_[0-9A-F-]{36}", "", name)
        # The artifact upload refuses some characters, for example quotes and colons.
        name = re.sub(r'[\\/"*:<>?|\r\n]', "-", name)
        shutil.copy(source / attachment["exportedFileName"], target / f"{prefix}-{name}")
        count += 1
print(f"{count} captures")
