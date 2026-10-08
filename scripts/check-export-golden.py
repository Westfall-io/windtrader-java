#!/usr/bin/env python3
"""CI golden-shape regression: batch-export the whole corpus and compare each
file's normalized shape (element count + @type multiset + dangling-ref count)
to the committed export-golden.json.

Usage: JAR=<path-to-jar> python3 scripts/check-export-golden.py [corpus-dir]
Corpus-dir defaults to src/test/resources/upstream (repo-relative).
"""
import json, collections, os, subprocess, sys

CORPUS = sys.argv[1] if len(sys.argv) > 1 else "src/test/resources/upstream"
GOLDEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "export-golden.json")
golden = json.load(open(GOLDEN))["manifest"]

# Run the single-JVM batch exporter over the whole corpus.
files = []
for root, _, fs in os.walk(CORPUS):
    for f in fs:
        if f.endswith(".sysml"):
            files.append(os.path.join(root, f))
files.sort()
lst = "\n".join(files) + "\n"
p = subprocess.run(["java", "-jar", os.environ["JAR"], "batch-export"],
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
    return len(d), dict(sorted(c.items())), dang

fails = 0
for path in files:
    rel = os.path.relpath(path, CORPUS)
    d = json.load(open(path + ".export.json"))
    cnt, types, dang = shape(d)
    g = golden.get(rel)
    if g is None:
        print(f"NO-GOLDEN {rel}")
        fails += 1
        continue
    if cnt != g["count"] or types != g["types"] or dang != g["dangling_refs"]:
        print(f"SHAPE-DIFF {rel}: count {cnt} (g {g['count']}), types {types==g['types']}, dangling {dang} (g {g['dangling_refs']})")
        fails += 1
print(f"Export golden check: {len(files)-fails}/{len(files)} matched, {fails} failed.")
if len(files) != 96:
    print(f"Expected 96 corpus files, found {len(files)}")
    sys.exit(1)
sys.exit(1 if fails else 0)
