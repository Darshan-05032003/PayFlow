#!/usr/bin/env python3
"""Token audit: screen code must use design-system tokens only.

Scans Kotlin under app/src/{main,debug}/kotlin/com/darshan/payflow/ui/, except ui/designsystem/ (where the tokens are
defined) and ui/theme/, for:

  colour    raw hex colours and named colours (Color(0xFF...), Color(1f, ...), "#RRGGBB", Color.White ...)
  size      non-zero .dp / .sp literals
  duration  numbers inside animation specs (tween, snap, delay, durationMillis, delayMillis, keyframes), raw spring(...),
            literal .milliseconds/.seconds and *_MS / *MILLIS constants
  easing    easing curves (CubicBezierEasing, LinearEasing, FastOutSlowInEasing, Ease*, Easing { })

A line that must break a rule says why with a trailing `// token-audit: allow (reason)` comment, on the line or the line
above. Comment lines and imports are ignored. TokenAuditTest runs the same rules on the JVM, so the test gate enforces them.

Usage: scripts/token_audit.py [project root]     exit 1 when anything is found
"""
import re
import sys
from pathlib import Path

RULES = {
    "colour": r'Color\(\s*0x|Color\(\s*[0-9]|"#[0-9A-Fa-f]{3,8}"|parseColor\(|Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b',
    "size": r'(?<![\w.])(?!0+(\.0+)?f?\.(dp|sp)\b)\d+(\.\d+)?f?\.(dp|sp)\b',
    "duration": r'\btween\(\s*\d|\bsnap\(\s*\d|\bdelay\(\s*\d|durationMillis\s*=\s*\d|delayMillis\s*=\s*\d|\bkeyframes\s*\{|\bspring\s*(<[^>]*>)?\(|\d+\.(milliseconds|seconds)\b|const val \w*(_MS|MILLIS)\w*\s*(:\s*\w+)?\s*=\s*\d',
    "easing": r'CubicBezierEasing\(|\b(LinearEasing|FastOutSlowInEasing|LinearOutSlowInEasing|FastOutLinearInEasing)\b|\bEase(In|Out|InOut)\w*\b|\bEasing\s*\{',
}
COMPILED = {name: re.compile(pattern) for name, pattern in RULES.items()}
ALLOW = "token-audit: allow"
SCANNED = ("app/src/main/kotlin/com/darshan/payflow/ui", "app/src/debug/kotlin/com/darshan/payflow/ui")
EXCLUDED = ("/ui/designsystem/", "/ui/theme/")


def screen_files(root: Path):
    for base in SCANNED:
        for path in sorted((root / base).rglob("*.kt")):
            if not any(part in path.as_posix() for part in EXCLUDED):
                yield path


def violations(path: Path):
    previous = ""
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        # An allowance covers its own line, or the next line when it stands alone as a comment.
        allowed_above = previous.startswith("//") and ALLOW in previous
        skip = stripped.startswith(("import ", "package ", "//", "*", "/*")) or ALLOW in line or allowed_above
        previous = stripped
        if skip:
            continue
        code = line.split("//", 1)[0]
        for name, rule in COMPILED.items():
            if rule.search(code):
                yield number, name, stripped


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent
    files = list(screen_files(root))
    found = 0
    for path in files:
        for number, rule, text in violations(path):
            print(f"{path.relative_to(root)}:{number}: [{rule}] {text}")
            found += 1
    if found:
        print(f"token audit: {found} violation(s) in {len(files)} files", file=sys.stderr)
        return 1
    print(f"token audit: clean ({len(files)} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
