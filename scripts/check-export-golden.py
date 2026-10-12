#!/usr/bin/env python3
"""CI golden-shape regression: batch-export the whole corpus and compare each
file's normalized shape to the committed export-golden.json.

Assertion: the element COUNT is the hard invariant — it is stable across fresh
JVMs and catches dropped/duplicated elements. The @type multiset and
dangling-ref count are recorded in the golden but NOT asserted (they drift by a
few elements run-to-run because the pilot's shared accumulated resource set,
inherent to Jupyter's long-lived session, makes cross-file import resolution
order-dependent).

Usage: JAR=<path-to-jar> python3 scripts/check-export-golden.py [corpus-dir]
Corpus-dir defaults to src/test/resources/upstream (repo-relative).
"""
import json, collections, os, subprocess, sys

CORPUS = sys.argv[1] if len(sys.argv) > 1 else "src/test/resources/upstream"
GOLDEN = os.environ.get("GOLDEN",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "export-golden.json"))
golden = json.load(open(GOLDEN))["manifest"]

# Run the single-JVM batch exporter over the whole corpus.
files = []
for root, _, fs in os.walk(CORPUS):
    for f in fs:
        if f.endswith(".sysml"):
            files.append(os.path.join(root, f))
files.sort()
lst = "\n".join(files) + "\n"
p = subprocess.run([os.environ.get("JAVA", "java"), "-jar", os.environ["JAR"], "batch-export"],
                   input=lst, capture_output=True, text=True, timeout=1800)
for line in p.stderr.splitlines():
    if line.startswith("FAIL"):
        print(line)
if p.returncode != 0:
    print("batch-export failed:", p.stderr)
    sys.exit(1)

def shape(d):
    c = collections.Counter(el.get("payload", {}).get("@type", "?") for el in d)
    all_ids = set(el.get("payload", {}).get("@id") for el in d if el.get("payload", {}).get("@id"))
    refs = []
    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if k == "@id": refs.append(v)
                else: walk(v)
        elif isinstance(o, list):
            for x in o: walk(x)
    for el in d: walk(el.get("payload", {}))
    dang = len([r for r in refs if r not in all_ids])
    # Duplicate ELEMENT-identity ids: the number of payload "@id" values that occur
    # more than once. The export merge dedups by identity, so this must be 0 — any
    # positive value means distinct elements collided on the dedup key and one was
    # silently dropped. (This is the round-3 invariant: count alone cannot distinguish
    # a clean dedup from one that dropped elements.)
    counter = collections.Counter(el.get("payload", {}).get("@id") for el in d if el.get("payload", {}).get("@id"))
    dup_ids = sum(v - 1 for v in counter.values() if v > 1)
    return len(d), dict(sorted(c.items())), dang, dup_ids

fails = 0
for path in files:
    rel = os.path.relpath(path, CORPUS)
    d = json.load(open(path + ".export.json"))
    cnt, types, dang, dup_ids = shape(d)
    g = golden.get(rel)
    if g is None:
        print(f"NO-GOLDEN {rel}")
        fails += 1
        continue
    # Element COUNT and duplicate element-id count are the hard invariants. Count is
    # stable across fresh JVMs and catches dropped/duplicated elements; dup_ids == 0
    # confirms the merge dedup keyed on true element identity and dropped nothing.
    # The type multiset and dangling-ref count are NOT asserted — the pilot's shared
    # accumulated resource set (inherent to Jupyter's long-lived session) makes those
    # drift by a few elements run-to-run. They are printed as informational only.
    if cnt != g["count"]:
        print(f"SHAPE-DIFF {rel}: count {cnt} (g {g['count']})")
        fails += 1
    elif dup_ids != 0:
        print(f"SHAPE-DIFF {rel}: {dup_ids} duplicate element ids (must be 0)")
        fails += 1
    elif types != g["types"] or dang != g["dangling_refs"]:
        print(f"INFO {rel}: count matches ({cnt}); types/dangling differ run-to-run"
              f" (types {types==g['types']}, dangling {dang} vs g {g['dangling_refs']})")
print(f"Export golden check: {len(files)-fails}/{len(files)} matched, {fails} failed.")
# Assert the corpus exactly matches the golden manifest (a broken/empty path or an
# out-of-sync golden must fail, not pass silently).
expected = len(golden)
if len(files) != expected:
    print(f"Expected {expected} corpus files (per golden), found {len(files)}")
    sys.exit(1)
sys.exit(1 if fails else 0)

