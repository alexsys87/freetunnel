#!/usr/bin/env python3
"""The Android app's icons, made from the desktop's.

The desktop draws assets/icons/*.svg and assets/logo.svg. The Android app draws the
same shapes from VectorDrawables, so that an icon is the same icon in both. This writes
android/app/src/main/res/drawable/ic_*.xml from those SVG files:

  - <path> data goes over as it is (only its spacing is made explicit: Android's
    parser is stricter than a browser about numbers run together);
  - <rect rx>, <line>, <circle> and <polyline> become path data, one to one;
  - stroke 2 on the 24 grid with round caps and joins, as the SVG says;
  - a filled <circle> stays filled (the speedometer's hub), and the logo's four
    arches keep their own greys: the app never tints the logo.

Tinted icons are white in the file and take their colour from the view that shows
them. The plus signs are not icons on the desktop either: it draws two rectangles,
16 by 1.8 in a 22 box on Configs and 10 by 1.6 in a 14 box on Home, and so do these.

  scripts/android-icons-from-svg.py           writes the drawables
  scripts/android-icons-from-svg.py --check   fails if one is out of date

tests/test_android_icons.sh runs --check, with fixtures.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# SVG file -> drawable. ic_servers was the Configs icon's old name.
ICONS = {
    "connection": "ic_connection",
    "network": "ic_network",
    "settings": "ic_settings",
    "log": "ic_log",
    "speedometer": "ic_ping",
    "export": "ic_export",
    "edit": "ic_edit",
    "close": "ic_close",
}
LOGO = "ic_logo"
# SVG files in assets/icons that the Android app has no use for.
DESKTOP_ONLY = set()
# The drawn plus signs: drawable, box, arm length, arm thickness (QML: ConfigsPage.qml
# and HomePage.qml draw them as two Rectangles with radius 1).
PLUSES = {
    "ic_add": (22, 16, 1.8),
    "ic_add_home": (14, 10, 1.6),
}

NS = "{http://www.w3.org/2000/svg}"
HEADER = ("<!-- Made by scripts/android-icons-from-svg.py from {source}. "
          "Change that, not this file. -->\n")
VECTOR = ('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{w}dp" '
          'android:height="{h}dp" android:viewportWidth="{vw}" android:viewportHeight="{vh}">\n{paths}</vector>\n')


def num(value):
    """A number as the shortest text that reads back to the same value."""
    value = float(value)
    if value == int(value):
        return str(int(value))
    return ("%.4f" % value).rstrip("0").rstrip(".")


def fnum(text):
    return float(text)


def tokens(d):
    return re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", d)


def path_data(d, where):
    """Path data with every number separate. The commands, and so the shape, stay."""
    out = []
    parts = tokens(d)
    # Anything the tokenizer could not read would change the shape silently.
    if "".join(re.sub(r"[\s,]", "", x) for x in parts) != re.sub(r"[\s,]", "", d):
        raise SystemExit("%s: path data this script cannot read: %s" % (where, d))
    i = 0
    command = None
    while i < len(parts):
        if re.match(r"[A-Za-z]", parts[i]):
            command = parts[i]
            out.append(command)
            i += 1
            continue
        if command in ("A", "a"):
            # rx ry rotation large-arc sweep x y: the two flags are 0 or 1 alone.
            args = parts[i:i + 7]
            if len(args) < 7 or args[3] not in ("0", "1") or args[4] not in ("0", "1"):
                raise SystemExit("%s: an arc this script cannot read: %s" % (where, d))
            out.append(" ".join(a if k in (3, 4) else num(a) for k, a in enumerate(args)))
            i += 7
        else:
            # Written out whole (".33" as "0.33"), for any parser, as the same number.
            if float(num(parts[i])) != float(parts[i]):
                raise SystemExit("%s: a number with more digits than this script keeps: %s" % (where, parts[i]))
            out.append(num(parts[i]))
            i += 1
    # "M 12 3" reads as well as "M12 3": no space after a command.
    return re.sub(r"([A-Za-z]) ", r"\1", " ".join(out))


def rect_path(e):
    x, y, w, h = (fnum(e.get(k, "0")) for k in ("x", "y", "width", "height"))
    rx = fnum(e.get("rx", e.get("ry", "0")))
    ry = fnum(e.get("ry", e.get("rx", "0")))
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    if rx == 0 and ry == 0:
        return "M%s,%s H%s V%s H%s Z" % (num(x), num(y), num(x + w), num(y + h), num(x))
    return ("M%s,%s H%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s H%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s Z" % (
        num(x + rx), num(y), num(x + w - rx),
        num(rx), num(ry), num(x + w), num(y + ry),
        num(y + h - ry),
        num(rx), num(ry), num(x + w - rx), num(y + h),
        num(x + rx),
        num(rx), num(ry), num(x), num(y + h - ry),
        num(y + ry),
        num(rx), num(ry), num(x + rx), num(y)))


def line_path(e):
    return "M%s,%s L%s,%s" % tuple(num(fnum(e.get(k))) for k in ("x1", "y1", "x2", "y2"))


def circle_path(e):
    cx, cy, r = (fnum(e.get(k)) for k in ("cx", "cy", "r"))
    return "M%s,%s A%s,%s 0 1 1 %s,%s A%s,%s 0 1 1 %s,%s Z" % (
        num(cx), num(cy - r), num(r), num(r), num(cx), num(cy + r), num(r), num(r), num(cx), num(cy - r))


def polyline_path(e):
    points = re.findall(r"[-+]?(?:\d+\.?\d*|\.\d+)", e.get("points"))
    pairs = [(points[i], points[i + 1]) for i in range(0, len(points) - 1, 2)]
    return "M%s,%s " % pairs[0] + " ".join("L%s,%s" % p for p in pairs[1:])


def stroke_attrs(svg, element):
    """What the SVG's stroke says, as the vector's: all of it must be what Android is told."""
    width = element.get("stroke-width", svg.get("stroke-width", "1"))
    cap = svg.get("stroke-linecap", "butt")
    join = svg.get("stroke-linejoin", "miter")
    return ('android:strokeColor="#ffffff" android:strokeWidth="%s" android:strokeLineCap="%s" android:strokeLineJoin="%s"'
            % (num(width), cap, join))


def icon(svg_path):
    svg = ET.parse(svg_path).getroot()
    box = svg.get("viewBox").split()
    if box[0] != "0" or box[1] != "0":
        raise SystemExit("%s: a viewBox that does not start at 0 0" % svg_path)
    if svg.get("fill") != "none":
        raise SystemExit("%s: this script draws outlined icons, with fill=\"none\" on the svg" % svg_path)
    # One <path> in the drawable for each element in the SVG, in the same order. Not
    # merged into one: a relative moveto that starts an SVG path is absolute, and
    # after another path's closing it would not be.
    shapes = []
    for e in svg.iter():
        tag = e.tag.replace(NS, "")
        if tag == "svg":
            continue
        if tag == "path":
            d = path_data(e.get("d"), svg_path)
        elif tag == "rect":
            d = rect_path(e)
        elif tag == "line":
            d = line_path(e)
        elif tag == "circle":
            d = circle_path(e)
        elif tag == "polyline":
            d = polyline_path(e)
        else:
            raise SystemExit("%s: an element this script does not convert: <%s>" % (svg_path, tag))
        if e.get("fill") == "currentColor" and e.get("stroke") == "none":
            shapes.append('    <path android:pathData="%s" android:fillColor="#ffffff"/>\n' % d)
        elif e.get("fill", "none") == "none" and e.get("stroke", "currentColor") == "currentColor":
            shapes.append('    <path android:pathData="%s" android:fillColor="@android:color/transparent" %s/>\n'
                          % (d, stroke_attrs(svg, e)))
        else:
            raise SystemExit("%s: a <%s> with a fill or stroke this script does not know" % (svg_path, tag))
    paths = "".join(shapes)
    w, h = box[2], box[3]
    return HEADER.format(source="assets/icons/" + svg_path.name) + \
        VECTOR.format(w=num(w), h=num(h), vw=num(w), vh=num(h), paths=paths)


def logo(svg_path):
    """The mark as it is: four filled arches in their own greys, never tinted."""
    svg = ET.parse(svg_path).getroot()
    box = svg.get("viewBox").split()
    paths = ""
    for e in svg.iter():
        if e.tag.replace(NS, "") != "path":
            continue
        fill = e.get("fill")
        if not fill or not re.match(r"#[0-9a-fA-F]{6}$", fill):
            raise SystemExit("%s: an arch without a plain colour" % svg_path)
        paths += '    <path android:pathData="%s" android:fillColor="%s"/>\n' % (path_data(e.get("d"), svg_path), fill.lower())
    if not paths:
        raise SystemExit("%s: no arches" % svg_path)
    return HEADER.format(source="assets/logo.svg") + VECTOR.format(
        w=num(box[2]), h=num(box[3]), vw=num(box[2]), vh=num(box[3]), paths=paths)


def capsule(x, y, w, h):
    """A rectangle with the ends of its short sides round, as QML's radius 1 gives one this thin."""
    r = min(1.0, w / 2, h / 2)
    return "M%s,%s H%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s H%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s Z" % (
        num(x + r), num(y), num(x + w - r), num(r), num(r), num(x + w), num(y + r), num(y + h - r),
        num(r), num(r), num(x + w - r), num(y + h), num(x + r), num(r), num(r), num(x), num(y + h - r),
        num(y + r), num(r), num(r), num(x + r), num(y))


def plus(box, arm, thickness):
    low = (box - thickness) / 2
    start = (box - arm) / 2
    paths = "    <path android:pathData=\"%s %s\" android:fillColor=\"#ffffff\"/>\n" % (
        capsule(start, low, arm, thickness), capsule(low, start, thickness, arm))
    return ("<!-- Made by scripts/android-icons-from-svg.py: the desktop draws this plus as two rectangles, "
            "%s by %s in a %s box. -->\n" % (num(arm), num(thickness), num(box))) + VECTOR.format(
        w=num(box), h=num(box), vw=num(box), vh=num(box), paths=paths)


def wanted(assets, res):
    """Every drawable this script owns: path -> text."""
    out = {}
    icons = Path(assets) / "icons"
    known = set(ICONS) | DESKTOP_ONLY
    for svg in sorted(icons.glob("*.svg")):
        if svg.stem not in known:
            raise SystemExit("%s has no drawable: add it to ICONS (or DESKTOP_ONLY) in this script" % svg)
    for name, drawable in ICONS.items():
        out[Path(res) / (drawable + ".xml")] = icon(icons / (name + ".svg"))
    out[Path(res) / (LOGO + ".xml")] = logo(Path(assets) / "logo.svg")
    for drawable, (box, arm, thickness) in PLUSES.items():
        out[Path(res) / (drawable + ".xml")] = plus(box, arm, thickness)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail if a drawable differs from what the SVG files give")
    parser.add_argument("--assets", default=str(ROOT / "assets"), help="the directory with icons/ and logo.svg")
    parser.add_argument("--res", default=str(ROOT / "android/app/src/main/res/drawable"), help="the drawable directory")
    args = parser.parse_args()
    made = wanted(args.assets, args.res)
    if not args.check:
        for path, text in made.items():
            path.write_text(text, encoding="utf-8")
        print("wrote %d drawables in %s" % (len(made), args.res))
        return 0
    problems = []
    for path, text in sorted(made.items()):
        if not path.is_file():
            problems.append("%s is missing" % path.name)
        elif path.read_text(encoding="utf-8") != text:
            problems.append("%s differs from what scripts/android-icons-from-svg.py makes of its SVG" % path.name)
    for stale in sorted(Path(args.res).glob("ic_*.xml")):
        if stale not in made:
            problems.append("%s is not made from an SVG: delete it, or add it to the script" % stale.name)
    for problem in problems:
        print("android-icons: " + problem, file=sys.stderr)
    if problems:
        print("android-icons: run scripts/android-icons-from-svg.py and commit what it writes", file=sys.stderr)
        return 1
    print("android icons: %d drawables match assets/" % len(made))
    return 0


if __name__ == "__main__":
    sys.exit(main())
