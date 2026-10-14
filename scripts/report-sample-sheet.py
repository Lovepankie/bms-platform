#!/usr/bin/env python3
"""Assembles the reviewed sample sheet of the report PDFs (ADR-034 quality bar item 8, ADR-038, issue #217).

`make document-samples` writes each report PDF and a PNG per page to backend/target/document-samples
(report-<name>-p<page>.png). This takes the first page of each report, and the last page of a report that
runs over several (where the cut note and the total row are), and lays them out in a grid with a caption
each, so the layout can be looked at in one picture: the heading of a column running into its neighbour, a
table cut at the page edge, a missing brand colour, a total that is not set off.

    python3 scripts/report-sample-sheet.py backend/target/document-samples docs/ui/report-samples/report-sample-sheet.png

Needs Pillow (pip install pillow). The pictures are fabricated documents; nothing real is in them.
"""
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw

CELL_WIDTH = 760
GAP = 24
CAPTION = 28
COLUMNS = 2


def pages(directory: Path):
    """Maps a report's name to its page images in page order."""
    found = {}
    for png in directory.glob("report-*-p*.png"):
        m = re.fullmatch(r"report-(.+)-p(\d+)\.png", png.name)
        if m:
            found.setdefault(m.group(1), {})[int(m.group(2))] = png
    return {name: [p[i] for i in sorted(p)] for name, p in sorted(found.items())}


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    source, target = Path(sys.argv[1]), Path(sys.argv[2])
    chosen = []
    for name, files in pages(source).items():
        chosen.append((f"{name}, page 1 of {len(files)}", files[0]))
        if len(files) > 1:
            chosen.append((f"{name}, page {len(files)} of {len(files)}", files[-1]))
    if not chosen:
        print(f"No report page images in {source}; run `make document-samples` first.")
        return 1
    tiles = []
    for caption, path in chosen:
        image = Image.open(path).convert("RGB")
        scale = CELL_WIDTH / image.width
        tiles.append((caption, image.resize((CELL_WIDTH, round(image.height * scale)))))
    rows = [tiles[i : i + COLUMNS] for i in range(0, len(tiles), COLUMNS)]
    heights = [max(t.height for _, t in row) + CAPTION for row in rows]
    sheet = Image.new(
        "RGB",
        (COLUMNS * CELL_WIDTH + (COLUMNS + 1) * GAP, sum(heights) + (len(rows) + 1) * GAP),
        "#e8e8e8",
    )
    draw = ImageDraw.Draw(sheet)
    y = GAP
    for row, height in zip(rows, heights):
        for col, (caption, tile) in enumerate(row):
            x = GAP + col * (CELL_WIDTH + GAP)
            draw.text((x, y + 6), caption, fill="#222222")
            sheet.paste(tile, (x, y + CAPTION))
        y += height + GAP
    target.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(target, optimize=True)
    print(f"{len(tiles)} pages in {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
