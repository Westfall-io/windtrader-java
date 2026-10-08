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
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

find "$CORPUS" -name '*.sysml' | sort > /tmp/golden-files.txt
N=$(wc -l < /tmp/golden-files.txt)
echo "generating golden for $N corpus files via batch-export..."
java -jar "$JAR" batch-export < /tmp/golden-files.txt > /tmp/golden-batch.log 2>&1
if [ $? -ne 0 ]; then
  echo "batch-export failed:"
  tail -20 /tmp/golden-batch.log
  exit 1
fi
grep -c "^OK " /tmp/golden-batch.log && echo "files exported"

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
for path in files:
    rel = os.path.relpath(path, corpus)
    d = json.load(open(path + ".export.json"))
    cnt, types, dang = shape(d)
    manifest[rel] = {"count": cnt, "types": types, "dangling_refs": dang}

with open(out, "w") as f:
    json.dump({"generated_by": "scripts/generate-export-golden.sh", "manifest": manifest}, f, indent=2)
    f.write("\n")
total = sum(m["count"] for m in manifest.values())
total_dang = sum(m["dangling_refs"] for m in manifest.values())
print(f"wrote {out}: {len(manifest)} files, {total} elements, {total_dang} dangling refs")
PY
echo "done"
