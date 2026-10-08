#!/usr/bin/env bash
# Generate export-golden.json from the corpus via batch-export and commit it.
#
#   scripts/generate-export-golden.sh <jar> <corpus-dir> <golden-out>
#
# Writes <golden-out> with the normalized shape of every .sysml file under
# <corpus-dir> (element count + @type multiset + dangling-ref count). This is the
# committed golden that CI's check-export-golden.py compares against. Re-run this
# script after an intentional grammar/library bump (then commit the new golden).
#
# Requires the windtrader-java jar: uses single-JVM batch-export (library loaded
# once, all corpus files exported in one JVM).
set -eu

JAR="${1:?usage: generate-export-golden.sh <jar> <corpus-dir> <golden-out>}"
CORPUS="${2:?usage: generate-export-golden.sh <jar> <corpus-dir> <golden-out>}"
GOLDEN_OUT="${3:?usage: generate-export-golden.sh <jar> <corpus-dir> <golden-out>}"

find "$CORPUS" -name '*.sysml' | sort > /tmp/golden-files.txt
N=$(wc -l < /tmp/golden-files.txt)
echo "generating golden for $N corpus files via batch-export..."
if ! java -jar "$JAR" batch-export < /tmp/golden-files.txt > /tmp/golden-batch.log 2>&1; then
  echo "batch-export failed:" >&2
  tail -20 /tmp/golden-batch.log >&2
  exit 1
fi
echo "exported $(grep -c '^OK ' /tmp/golden-batch.log) files"

CORPUS="$CORPUS" GOLDEN_OUT="$GOLDEN_OUT" python3 - <<'PY'
import json, collections, os, sys
corpus = os.environ["CORPUS"]
out = os.environ["GOLDEN_OUT"]

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

files = []
for root, _, fs in os.walk(corpus):
    for f in fs:
        if f.endswith(".sysml"):
            files.append(os.path.join(root, f))
files.sort()

manifest = {}

total_elements = 0
total_dangling = 0
for path in files:
    rel = os.path.relpath(path, corpus)
    d = json.load(open(path + ".export.json"))
    cnt, types, dang = shape(d)
    manifest[rel] = {"count": cnt, "dangling_refs": dang, "types": types}
    total_elements += cnt
    total_dangling += dang

# Schema-identical to the committed export-golden.json: top-level files/ok/totals
# (ok = number of files passed, an int), entries keyed by relative path with
# {count, dangling_refs, types}; sorted keys so regeneration is byte-stable.
golden = {
    "files": len(manifest),
    "manifest": manifest,
    "ok": len(manifest),
    "total_dangling_refs": total_dangling,
    "total_elements": total_elements,
}
with open(out, "w") as f:
    json.dump(golden, f, indent=2, sort_keys=True)
    f.write("\n")
print(f"wrote {out}: {len(manifest)} files, {total_elements} elements, {total_dangling} dangling refs")
PY
