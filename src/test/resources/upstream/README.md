# Upstream SysML v2 example corpus (test fixtures)

These `.sysml` files are test fixtures copied from the canonical SysML v2 reference
repository:

- Source: https://github.com/Systems-Modeling/SysML-v2-Release
- Path: `sysml/src/examples/`
- Pinned upstream commit: `fb97b754f29588b8e9c7a35f370880cd15eb29e7` ("Updated for 2026-08", 2026-09-11)
- License: **EPL-2.0** (same as windtrader-java)

Local directory names are hyphenated (`Simple-Tests`, `Mass-Rollup`, `Import-Tests`,
`Vehicle-Example`) while upstream uses spaces (`Simple Tests`, `Mass Roll-up Example`);
paths in this repo do NOT match upstream verbatim. To re-sync, copy from the pinned
commit and re-hyphenate.

They are used to prove that `windtrader-java check` accepts valid SysML v2 models
(parse-only round trip: parse -> echo -> re-parse). They are NOT authored by Westfall-io
and are included purely as conformance fixtures.

## Known 0.60.0 grammar gaps (pre-existing, not regressions)

The following canvas-fixtures contain SysML v2 constructs the pinned pilot grammar
(0.60.0) does not yet accept; they fail with `error: line=... offset=... near=...`
syntax errors both before and after the RoundTrip driver fix:

- `Simple-Tests/DecisionTest.sysml` — `succession ... if/then` body
- `Simple-Tests/InterfaceTest.sysml` — `abstract interface i = i1;` alias
- `Simple-Tests/RequirementTest.sysml` — `assume`/`require`/`frame` keywords
- `Simple-Tests/ViewTest.sysml` — `view`/`render` constructs

If a future pilot pin accepts them, they should move into the expected-pass set.
