/**
 * semantic-release config for gitmoji-only commits.
 *
 * Commit examples:
 *   ✨ Add feature
 *   🐛 Fix bug
 *   💥 Breaking change
 *   📝 Docs
 *   🔧 CI/maintenance
 */

const headerPattern = /^(\S+)\s(.*)$/;

const parserOpts = {
  headerPattern,
  headerCorrespondence: ["type", "subject"],
};

const allowedTypes = new Set(["💥", "✨", "🐛", "📝", "🔧", "⚙️"]);

const sectionByType = {
  "💥": "Breaking Changes",
  "✨": "Features",
  "🐛": "Bug Fixes",
  "📝": "Documentation",
  "🔧": "Maintenance",
  "⚙️": "Maintenance",
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
            if (!allowedTypes.has(commit.type)) return null;

            // IMPORTANT: do not mutate commit (can be immutable)
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

    // Generates/updates CHANGELOG.md
    ["@semantic-release/changelog", { changelogFile: "CHANGELOG.md" }],

    /**
     * Create exactly ONE release jar named with the semantic-release version:
     *   target/windtrader-java-${nextRelease.version}.jar
     *
     * Assumption: your workflow already ran `mvn ... package` and produced target/*.jar
     */
    [
      "@semantic-release/exec",
      {
        prepareCmd:
          "bash -lc \"set -e; JAR=$(ls -1 target/*.jar 2>/dev/null | grep -v '^target/original-' | head -n 1); " +
          "if [ -z \\\"$JAR\\\" ]; then echo 'No built jar found in target/. Did CI run mvn package?'; ls -lah target || true; exit 1; fi; " +
          "echo \\\"Using built jar: $JAR\\\"; " +
          "cp \\\"$JAR\\\" \\\"target/windtrader-java-${nextRelease.version}.jar\\\"; " +
          "rm -f target/windtrader-java-*.jar; " +
          "mv \\\"target/windtrader-java-${nextRelease.version}.jar\\\" \\\"target/windtrader-java-${nextRelease.version}.jar\\\"; " +
          "ls -lah target | sed -n '1,120p'\"",
      },
    ],

    // Create GitHub Release + upload jar asset(s)
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

    // Commit CHANGELOG.md back to main
    [
      "@semantic-release/git",
      {
        assets: ["CHANGELOG.md"],
        // IMPORTANT: normal string, not a JS template literal
        message: "🔖 Release v${nextRelease.version}\n\n[skip ci]",
      },
    ],
  ],
};
