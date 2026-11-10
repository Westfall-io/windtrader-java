#!/usr/bin/env python3
"""Round-trip regression: unrestricted names must export WITHOUT the textual-notation
single quotes in declaredName / declaredShortName.

The pilot's in-memory model stores unrestricted names clean (e.g. ``My Part``, short
name ``R-1``), but its toJsonTree serializer re-wraps them in single quotes (with
escapes) when producing JSON. RoundTrip unquotes these two fields to match the model.
This script drives a fresh JVM over inputs exercising:

  * a quoted full name      (part def 'My Part')
  * a quoted short name     (requirement def <'R-1'> Req)
  * a basic identifier      (part def Plain / <r2>)   -- must stay untouched
  * an escaped quote        (part def 'It\\'s Here')   -- ' --> ' and \\ --> \

Assert: every declaredName / declaredShortName is free of the surrounding single
quotes, and basic identifiers are unchanged. This is part of the round-trip standard:
parse -> canonicalize(export) -> emit -> the emitted names must match what the model
holds.

Usage: JAVA=<path-to-java> WINDTRADER_SYSML_LIBRARY=<lib> python3 scripts/check-export-names.py
JAR is read from the JAR env var.
"""
import json, os, subprocess, sys

JAVA = os.environ.get("JAVA", "java")
JAR = os.environ.get("JAR")
if not JAR:
    print("JAR env var required")
    sys.exit(1)

def export(text):
    p = subprocess.run(
        [JAVA, "-jar", JAR, "export"],
        input=text, capture_output=True, text=True, timeout=300)
    if p.returncode != 0:
        raise RuntimeError(f"export failed rc={p.returncode}: {p.stderr}")
    return json.loads(p.stdout)

def names(d):
    out = {}
    for it in d:
        pl = it.get("payload", {})
        nm = pl.get("declaredName")
        sn = pl.get("declaredShortName")
        key = (pl.get("@type"), nm)
        if nm is not None or sn is not None:
            out[key] = sn
    return out

cases = {
    "package P { requirement def <'R-1'> Req; part def 'My Part'; part def <r2> Plain; }": {
        # (type, declaredName) -> expected declaredShortName
        ("RequirementDefinition", "Req"): "R-1",   # quoted short name unquoted
        ("PartDefinition", "My Part"): None,        # quoted full name unquoted
        ("PartDefinition", "Plain"): "r2",          # basic identifier untouched
    },
    "package Q { part def 'It\\'s Here'; }": {
        ("PartDefinition", "It's Here"): None,      # escaped quote resolved
    },
}

fails = 0
for text, expected in cases.items():
    try:
        got = names(export(text))
    except Exception as e:
        print(f"FAIL export errored: {e}")
        fails += 1
        continue
    for (typ, nm), sn in expected.items():
        if (typ, nm) not in got:
            print(f"FAIL missing ({typ}, {nm!r}); got keys subset {list(got.items())[:8]}")
            fails += 1
            continue
        got_sn = got[(typ, nm)]
        if got_sn != sn:
            print(f"FAIL ({typ}, {nm!r}) declaredShortName {got_sn!r} != {sn!r}")
            fails += 1
    # no key may still carry a surrounding-quote wrapping
    for (typ, nm), sn in got.items():
        for label, v in (("declaredName", nm), ("declaredShortName", sn)):
            if v is None:
                continue
            if v.startswith("'") or v.endswith("'"):
                print(f"FAIL ({typ}, {nm!r}) {label} still quoted: {v!r}")
                fails += 1

print(f"Export name-fidelity check: {len(cases)} case(s), {fails} failure(s).")
sys.exit(1 if fails else 0)
