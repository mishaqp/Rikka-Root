#!/usr/bin/env python3
"""Compare fixed message crops after integer vertical alignment, without resizing.

This QA check excludes the intentionally changed header and composer. Every
pixel in the fixed 411px-wide message rectangle is compared. Modal tool-detail
content uses a fixed screen rectangle and does not need translation. PNGs are
read only; mismatches create diagnostic crops outside the baseline directories.
"""

import argparse
import json
from pathlib import Path
import zlib

import numpy as np
from PIL import Image, ImageChops


def pixels(path):
    return np.asarray(Image.open(path).convert("RGBA"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    base = Path(__file__).resolve().parent
    parser.add_argument("--before", type=Path, default=base / "chat-before")
    parser.add_argument("--after", type=Path, default=base / "chat-after-stage1")
    parser.add_argument("--output", type=Path, default=Path("/tmp/chat-stage1-message-qa"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    reports = []
    for path in sorted(args.before.glob("*/*.png")):
        relative = path.relative_to(args.before)
        current_path = args.after / relative
        if not current_path.exists():
            raise SystemExit(f"Missing AFTER counterpart: {current_path}")
        before, after = pixels(path), pixels(current_path)
        if before.shape != (891, 411, 4) or before.shape != after.shape:
            raise SystemExit(f"Unexpected image size: {relative}")
        if path.stem.startswith("tool_") and path.stem.endswith("_details"):
            delta = 0
            top, bottom = 225, 891
        else:
            # Measure a translation from the same fixed interior rectangle. This
            # resolves normal top-anchored vs clamped end-of-list viewports. The
            # final rectangle is broader and independent of the matching rows.
            old_rows = np.array([zlib.crc32(row.tobytes()) for row in before])
            new_rows = np.array([zlib.crc32(row.tobytes()) for row in after])
            delta = min(range(-120, 121), key=lambda offset: np.count_nonzero(
                old_rows[220:580] != new_rows[220 + offset:580 + offset]
            ))
            top, bottom = max(80, 72 - delta), min(700, 700 - delta)
        old_crop, new_crop = before[top:bottom], after[top + delta:bottom + delta]
        changed = int(np.count_nonzero(np.any(old_crop != new_crop, axis=2)))
        reports.append({
            "capture": str(relative), "vertical_offset": delta,
            "before_crop": [0, top, 411, bottom],
            "after_crop": [0, top + delta, 411, bottom + delta],
            "compared_pixels": int(old_crop.shape[0] * old_crop.shape[1]),
            "changed_pixels": changed,
        })
        if changed:
            destination = args.output / relative.parent / path.stem
            destination.mkdir(parents=True, exist_ok=True)
            old_image, new_image = Image.fromarray(old_crop), Image.fromarray(new_crop)
            old_image.save(destination / "before.png")
            new_image.save(destination / "after.png")
            # Convert to RGB because an all-zero alpha difference hides RGB diffs.
            ImageChops.difference(old_image.convert("RGB"), new_image.convert("RGB")).save(destination / "diff.png")
    summary = {
        "captures": len(reports),
        "equal_captures": sum(item["changed_pixels"] == 0 for item in reports),
        "compared_pixels": sum(item["compared_pixels"] for item in reports),
        "changed_pixels": sum(item["changed_pixels"] for item in reports),
        "captures_detail": reports,
    }
    (args.output / "report.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: value for key, value in summary.items() if key != "captures_detail"}))
    print(f"Report: {args.output / 'report.json'}")
    return 0 if summary["captures"] == 208 and summary["changed_pixels"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
