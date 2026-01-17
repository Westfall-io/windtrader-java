# windtrader-java

**windtrader-java** is a small, headless **SysML v2 syntax validator** built on the OMG SysML v2 Pilot Implementation (Xtext/EMF). It is designed to be embedded in other tooling (especially a Python wrapper) as a stable “validation engine” that can run in CI and local pipelines.

This repository produces a single runnable **shaded JAR** with a minimal CLI:

- `check` — validate SysML v2 text from **stdin** (parse-only)
- `echo` — validate then print the parsed text (useful smoke test)
- `versions` — print runtime metadata (intended for wrappers)

> Current behavior is **parse-only**. It does not require model resolution/imports and does not attempt semantic validation. That makes it fast and predictable for wrapper pipelines.

---

## Quick start (local)

### Build the shaded JAR

```bash
mvn -q -DskipTests=true clean package
```

You should see something like:

- `target/windtrader-java-0.1.0.jar` (runnable shaded jar)
- `target/original-windtrader-java-0.1.0.jar` (non-shaded original)

### Validate SysML v2 text

Valid input returns **exit code 0**:

```bash
echo "part Stage_1 { attribute mass; }"   | java -jar target/windtrader-java-0.1.0.jar check
echo $?
# 0
```

Invalid syntax returns **exit code 2** and prints an error location:

```bash
echo "part Stage_1 { attrib mass; }"   | java -jar target/windtrader-java-0.1.0.jar check
echo $?
# error: line=1 offset=22 near=mass
# 2
```

Echo mode prints the parsed text (useful for smoke tests):

```bash
echo "part Stage_1 { attribute mass; }"   | java -jar target/windtrader-java-0.1.0.jar echo
```

---

## CLI reference

### `check`

- Reads stdin
- Parses using SysML v2 Xtext parser
- Prints syntax error locations to stderr (best-effort)
- Exit codes:
  - `0` — valid (parse succeeded, no syntax errors)
  - `2` — invalid (syntax errors / no root element)
  - `3` — runtime error (unexpected exception)

### `echo`

Same as `check`, but prints the parsed text if valid.

### `versions`

Prints metadata for wrapper integration. Example:

```bash
java -jar target/windtrader-java-0.1.0.jar versions
```

Output:

```
name=windtrader-java
mode=validator
validation=parse-only
java_min=21
sysml_version=unknown
```

> `sysml_version` is currently reported as `unknown` unless explicitly wired. The Python wrapper should treat this as “best-effort info,” not a hard contract.

---

## Using GitHub Packages (SysML dependency)

The SysML v2 Pilot Implementation artifacts are hosted on GitHub Packages. To build, Maven must be able to authenticate to:

- `https://maven.pkg.github.com/Systems-Modeling/SysML-v2-Pilot-Implementation`

### Local builds

Create a GitHub Personal Access Token (PAT) with **read:packages** and configure Maven credentials for the repo id used in `pom.xml` (`sysml-github`).

Typical setup is a `~/.m2/settings.xml` entry like:

```xml
<settings>
  <servers>
    <server>
      <id>sysml-github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

---

## CI and Releases

This repo commonly uses two workflows:

- **CI**: build + smoke test + upload jar artifact
- **Release**: run semantic-release (gitmoji rules) and attach the jar as a GitHub Release asset

### Why not “depend” workflows on each other?

GitHub Actions workflows are usually independent. Instead of making one workflow “call” another, you typically:
- keep CI on every push/PR, and
- keep Release only on `main` (or tags), and
- ensure Release runs the same build steps (so it cannot release a broken jar).

This keeps the release path self-contained and reproducible.

---

## Integration with windtrader (Python wrapper)

The wrapper should treat this jar as a black-box validator:

- Provide SysML v2 text on stdin
- Inspect exit codes and stderr
- For multi-version support:
  - select jar version based on requested SysML pilot version
  - run `versions` to report metadata to the user

---

## Development notes

- **Java**: 21+
- **Build**: Maven + shade plugin to create a single runnable jar
- **Validation**: parse-only (fast); no semantic validation, no import resolution
- **Logging**: depends on upstream Xtext/log4j behavior (noise may appear if not configured)

---

## License

This project is licensed under the **GNU General Public License v3.0**. See `LICENSE.md`.

---

## Acknowledgements

- Built on the OMG **SysML v2 Pilot Implementation** (Xtext/EMF-based).
