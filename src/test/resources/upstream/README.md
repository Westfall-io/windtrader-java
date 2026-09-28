# Upstream SysML v2 example corpus (test fixtures)

These `.sysml` files are test fixtures copied from the canonical SysML v2 reference
repository:

- Source: https://github.com/Systems-Modeling/SysML-v2-Release
- Path: `sysml/src/examples/`
- Pinned upstream commit: `fb97b754f29588b8e9c7a35f370880cd15eb29e7` ("Updated for 2026-08", 2026-09-11)
- License: **EPL-2.0** (same as windtrader-java)

They are used to prove that `windtrader-java check` accepts valid SysML v2 models
(parse-only round trip: parse -> echo -> re-parse). They are NOT authored by Westfall-io
and are included purely as conformance fixtures.

## Scope

The corpus is the **complete** `sysml/src/examples` tree: all **96** `.sysml` files
across **22** example directories. With the pilot grammar pinned at **0.62.0**, every
file in the corpus is expected to parse clean (exit 0) — the CI corpus step fails on any
regression.

## Directory naming

Local directory names are hyphenated (`Simple-Tests`, `Mass-Rollup`, `Import-Tests`,
`Vehicle-Example`) while upstream uses spaces (`Simple Tests`, `Mass Roll-up Example`,
`Vehicle Example`); paths in this repo do NOT match upstream verbatim. To re-sync, copy
from the pinned commit and re-hyphenate.

## Historical note: 0.60.0 grammar gaps

Under the previous pilot pin (0.60.0), four files failed with a pre-existing grammar gap:
`DecisionTest.sysml` (`succession ... if/then`), `InterfaceTest.sysml` (`abstract
interface =` alias), `RequirementTest.sysml` (`assume`/`require`/`frame`), and
`ViewTest.sysml` (`view`/`render`). These constructs are accepted by the 0.62.0 grammar,
so the gap and its CI skip-list were removed when the project moved to 0.62.0.
