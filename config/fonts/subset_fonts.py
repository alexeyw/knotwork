#!/usr/bin/env python3
"""Cut the bundled fonts (app/src/main/res/font/) from the upstream releases.

The app ships Inter and JetBrains Mono in four weights each, cut down to the
characters listed in `characters.txt` next to this script. This script is how
those eight files are made, so a later change to the list rebuilds them the same
way.

Upstream (SIL Open Font License 1.1, no reserved font names):
  Inter 4.0           https://github.com/rsms/inter/releases/download/v4.0/Inter-4.0.zip
                      files `extras/ttf/Inter-{Regular,Medium,SemiBold,Bold}.ttf`
  JetBrains Mono 2.304 https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip
                      files `fonts/ttf/JetBrainsMono-{Regular,Medium,SemiBold,Bold}.ttf`

What the cut keeps: the listed characters, the glyphs the kept layout features
reach from them, and those features — `ccmp`, `locl`, `mark`, `mkmk` in both
families plus `kern` in Inter. Everything else goes: hinting, code ligatures
(`calt`), stylistic sets, fractions. Glyph shapes are not changed.

Made with fontTools 4.60.2 (`pip install fonttools==4.60.2`); another version may
lay the files out differently.

Usage:
  python3 config/fonts/subset_fonts.py --inter <dir> --mono <dir> \\
      [--out app/src/main/res/font] [--compare <dir with the previous files>]

`--inter` and `--mono` name the folders holding the upstream TTFs above. With
`--compare`, every character of a previous file must keep its outline, advance
width and kerning in the new one, and the vertical metrics must not change —
text that rendered before renders the same.
"""

import argparse
import os
import sys

import fontTools
from fontTools import subset
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.ttLib import TTFont

TESTED_FONTTOOLS = "4.60.2"
HERE = os.path.dirname(os.path.abspath(__file__))

# Output file -> (family flag, upstream file name).
FONTS = {
    "inter_regular.ttf": ("inter", "Inter-Regular.ttf"),
    "inter_medium.ttf": ("inter", "Inter-Medium.ttf"),
    "inter_semibold.ttf": ("inter", "Inter-SemiBold.ttf"),
    "inter_bold.ttf": ("inter", "Inter-Bold.ttf"),
    "jetbrains_mono_regular.ttf": ("mono", "JetBrainsMono-Regular.ttf"),
    "jetbrains_mono_medium.ttf": ("mono", "JetBrainsMono-Medium.ttf"),
    "jetbrains_mono_semibold.ttf": ("mono", "JetBrainsMono-SemiBold.ttf"),
    "jetbrains_mono_bold.ttf": ("mono", "JetBrainsMono-Bold.ttf"),
}

LAYOUT_FEATURES = {
    "inter": ["ccmp", "locl", "mark", "mkmk", "kern"],
    "mono": ["ccmp", "locl", "mark", "mkmk"],
}

VERTICAL_METRICS = (
    ("hhea", ("ascent", "descent", "lineGap")),
    ("OS/2", ("sTypoAscender", "sTypoDescender", "sTypoLineGap", "usWeightClass")),
    ("head", ("unitsPerEm",)),
)


def read_characters(path):
    """Code points of the list at `path`: `U+XXXX` or `U+XXXX..U+YYYY` per line, `#` comments."""
    codes = set()
    with open(path, encoding="utf-8") as f:
        for number, raw in enumerate(f, 1):
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            bounds = [part.strip() for part in line.split("..")]
            try:
                values = [int(b[2:], 16) for b in bounds if b.upper().startswith("U+")]
            except ValueError:
                values = []
            if len(values) != len(bounds) or len(values) not in (1, 2):
                sys.exit(f"{path}:{number}: not a code point or range: {raw.rstrip()}")
            codes.update(range(values[0], values[-1] + 1))
    return codes


def cut(source, target, characters, features):
    """Write `source` cut to `characters` with `features` to `target`."""
    options = subset.Options()
    options.hinting = False
    options.layout_features = features
    font = subset.load_font(source, options)
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=characters)
    subsetter.subset(font)
    subset.save_font(font, target, options)


def outline(glyph_set, name):
    pen = DecomposingRecordingPen(glyph_set)
    glyph_set[name].draw(pen)
    return pen.value


def kerning(font):
    """Pair kerning of the `kern` feature as {(code point, code point): x advance}."""
    pairs = {}
    if "GPOS" not in font or not font["GPOS"].table.FeatureList:
        return pairs
    table = font["GPOS"].table
    lookups = sorted({i for record in table.FeatureList.FeatureRecord if record.FeatureTag == "kern"
                      for i in record.Feature.LookupListIndex})
    by_glyph = {}
    for code, glyph in font.getBestCmap().items():
        by_glyph.setdefault(glyph, []).append(code)
    glyphs = font.getGlyphOrder()
    for index in lookups:
        lookup = table.LookupList.Lookup[index]
        for sub in lookup.SubTable:
            if lookup.LookupType == 9:
                sub = sub.ExtSubTable
            if getattr(sub, "LookupType", 2) != 2:
                continue
            found = {}
            if sub.Format == 1:
                for first, pair_set in zip(sub.Coverage.glyphs, sub.PairSet):
                    for record in pair_set.PairValueRecord:
                        found[(first, record.SecondGlyph)] = getattr(record.Value1, "XAdvance", 0) or 0
            elif sub.Format == 2:
                classes = {}
                for glyph in glyphs:
                    classes.setdefault(sub.ClassDef2.classDefs.get(glyph, 0), []).append(glyph)
                for first in sub.Coverage.glyphs:
                    row = sub.Class1Record[sub.ClassDef1.classDefs.get(first, 0)]
                    for cls, record in enumerate(row.Class2Record):
                        value = getattr(record.Value1, "XAdvance", 0) or 0
                        for second in classes.get(cls, []):
                            found[(first, second)] = value
            for (first, second), value in found.items():
                for a in by_glyph.get(first, []):
                    for b in by_glyph.get(second, []):
                        pairs.setdefault((a, b), value)
    return {pair: value for pair, value in pairs.items() if value}


def compare(previous_path, new_path):
    """Problems that would change how text drawn with the previous file renders."""
    old, new = TTFont(previous_path), TTFont(new_path)
    old_map, new_map = old.getBestCmap(), new.getBestCmap()
    old_glyphs, new_glyphs = old.getGlyphSet(), new.getGlyphSet()
    problems = []
    for code, name in sorted(old_map.items()):
        if code not in new_map:
            problems.append(f"U+{code:04X} is gone")
            continue
        if outline(old_glyphs, name) != outline(new_glyphs, new_map[code]):
            problems.append(f"U+{code:04X} {chr(code)!r} changed its outline")
        if old["hmtx"][name] != new["hmtx"][new_map[code]]:
            problems.append(f"U+{code:04X} {chr(code)!r} changed its metrics")
    old_kerning = kerning(old)
    new_kerning = {pair: value for pair, value in kerning(new).items()
                   if pair[0] in old_map and pair[1] in old_map}
    changed = sorted(pair for pair in set(old_kerning) | set(new_kerning)
                     if old_kerning.get(pair) != new_kerning.get(pair))
    if changed:
        problems.append(f"{len(changed)} kerning pairs changed, e.g. "
                        + ", ".join(f"{chr(a)}{chr(b)}" for a, b in changed[:5]))
    for table, fields in VERTICAL_METRICS:
        for field in fields:
            before, after = getattr(old[table], field), getattr(new[table], field)
            if before != after:
                problems.append(f"{table}.{field} {before} -> {after}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--inter", required=True, help="folder with the Inter 4.0 static TTFs")
    parser.add_argument("--mono", required=True, help="folder with the JetBrains Mono 2.304 static TTFs")
    parser.add_argument("--out", default=os.path.join(HERE, "..", "..", "app", "src", "main", "res", "font"))
    parser.add_argument("--compare", help="folder with the previous files to compare against")
    args = parser.parse_args()

    if fontTools.version != TESTED_FONTTOOLS:
        print(f"warning: fontTools {fontTools.version}, the files were made with {TESTED_FONTTOOLS}",
              file=sys.stderr)
    characters = read_characters(os.path.join(HERE, "characters.txt"))
    sources = {"inter": args.inter, "mono": args.mono}
    failed = False
    for target_name, (family, source_name) in FONTS.items():
        source = os.path.join(sources[family], source_name)
        target = os.path.join(args.out, target_name)
        cut(source, target, characters, LAYOUT_FEATURES[family])
        font = TTFont(target)
        covered = font.getBestCmap()
        absent = sorted(characters - set(covered))
        line = (f"{target_name:28} {os.path.getsize(target):7} bytes, {font['maxp'].numGlyphs:4} glyphs, "
                f"{len(covered):4} characters; not in the typeface: "
                + (" ".join(f"U+{c:04X}" for c in absent) or "none"))
        print(line)
        if args.compare:
            problems = compare(os.path.join(args.compare, target_name), target)
            for problem in problems:
                print(f"    {problem}")
            failed = failed or bool(problems)
    if failed:
        sys.exit("text drawn with the previous files would render differently (see above)")


if __name__ == "__main__":
    main()
