/**
 * semantic-release config for gitmoji-only commits + jar asset publishing.
 *
 * Commit examples:
 *   ✨ Add feature
 *   🐛 Fix bug
 *   💥 Breaking change
 *   📝 Docs
 *   🔧 CI / maintenance
 *   ⚙️  Maintenance
 */

const headerPattern = /^(\S+)\s(.*)$/;

const parserOpts = {
  headerPattern,
  headerCorrespondence: ["type", "subject"],
};

module.exports = {
  branches: ["main"],
  tagFormat: "v${version}",

  plugins: [
    [
      "@semantic-release/commit-analyzer",
      {
        preset: false,
        parserOpts,
        releaseRules: [
          { type: "💥", release: "major" },
          { type: "✨", release: "minor" },
          { type: "🐛", release: "patch" },
          { type: "📝", release: "patch" },
          { type: "🔧", release: "patch" },
          { type: "⚙️", release: "patch" },
        ],
      },
    ],

    [
      "@semantic-release/release-notes-generator",
      {
        preset: false,
        parserOpts,
        writerOpts: {
          transform: (commit) => {
            const allowed = new Set(["💥", "✨", "🐛", "📝", "🔧", "⚙️"]);
            if (!allowed.has(commit.type)) return null;

            const sectionByType = {
              "💥": "Breaking Changes",
              "✨": "Features",
              "🐛": "Bug Fixes",
              "📝": "Documentation",
              "🔧": "Maintenance",
              "⚙️": "Maintenance",
            };

            // IMPORTANT: return a new object (do not mutate commit)
            return {
              ...commit,
              type: sectionByType[commit.type] || "Other",
              shortHash: commit.hash ? commit.hash.substring(0, 7) : "",
            };
          },
          groupBy: "type",
          commitGroupsSort: (a, b) => {
            const order = [
              "Breaking Changes",
              "Features",
              "Bug Fixes",
              "Documentation",
              "Maintenance",
              "Other",
            ];
            return order.indexOf(a.title) - order.indexOf(b.title);
          },
          commitsSort: ["scope", "subject"],
        },
      },
    ],

    ["@semantic-release/changelog", { changelogFile: "CHANGELOG.md" }],

    /**
     * Rename/canonicalize the Maven-built shaded jar so ONLY ONE jar is eligible for upload:
     *   target/windtrader-java-${nextRelease.version}.jar
     *
     * NOTE: This assumes your workflow already ran `mvn ... package` before `npx semantic-release`.
     */
    [
      "@semantic-release/exec",
      {
        prepareCmd:
          'bash -lc \'set -euo pipefail; ' +
          // Find the built shaded jar (exclude original-*.jar)
          'JAR="$(find target -maxdepth 1 -type f -name "*.jar" ! -name "original-*.jar" | head -n 1)"; ' +
          'if [ -z "$JAR" ]; then echo "No built jar found in target/. Did the workflow run mvn package?"; ls -lah target || true; exit 1; fi; ' +
          'echo "Found built jar: $JAR"; ' +
          // Copy to temp first so cleanup can't delete the source
          'TMP="target/_windtrader_tmp.jar"; cp "$JAR" "$TMP"; ' +
          // Remove any previously-canonicalized jars
          'rm -f target/windtrader-java-*.jar target/windtrader-java.jar; ' +
          // Create the single canonical jar for this release version
          'mv "$TMP" "target/windtrader-java-${nextRelease.version}.jar"; ' +
          'ls -lah target | sed -n "1,200p"\'',
      },
    ],

    [
      "@semantic-release/github",
      {
        assets: [
          {
            path: "target/windtrader-java-*.jar",
            label: "windtrader-java shaded jar",
          },
        ],
      },
    ],

    [
      "@semantic-release/git",
      {
        assets: ["CHANGELOG.md"],
        // IMPORTANT: normal string, NOT a JS template literal
        message: "🔖 Release v${nextRelease.version}\n\n[skip ci]",
      },
    ],
  ],
};
