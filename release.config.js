/**
 * semantic-release config for gitmoji-only commits + attaching the shaded jar asset.
 *
 * Commit examples:
 *   ✨ Add feature
 *   🐛 Fix bug
 *   💥 Breaking change
 *   📝 Docs
 *   🔧 CI/maintenance
 *   ⚙️ Maintenance
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
            if (!allowed.has(commit.type)) return;

            const sectionByType = {
              "💥": "Breaking Changes",
              "✨": "Features",
              "🐛": "Bug Fixes",
              "📝": "Documentation",
              "🔧": "Maintenance",
              "⚙️": "Maintenance",
            };

            // Do NOT mutate commit (can be immutable); return a new object instead.
            return {
              ...commit,
              type: sectionByType[commit.type] || "Other",
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
          commitsSort: ["subject"],
        },
      },
    ],

    // Create/append CHANGELOG.md each release
    ["@semantic-release/changelog", { changelogFile: "CHANGELOG.md" }],

    /**
     * Rename the Maven-built jar into the release version, and ensure only ONE jar matches the upload pattern.
     *
     * This runs AFTER semantic-release has calculated nextRelease.version.
     */
    [
      "@semantic-release/exec",
      {
        prepareCmd:
          'bash -lc \'set -euo pipefail; ' +
          // Find any jar that is NOT original-*.jar
          'JAR="$(find target -maxdepth 1 -type f -name \"*.jar\" ! -name \"original-*.jar\" | head -n 1)\"; ' +
          'if [ -z \"$JAR\" ]; then echo \"No built jar found in target/. Did the workflow run mvn package?\"; ls -lah target || true; exit 1; fi; ' +
          'echo \"Using built jar: $JAR\"; ' +
          // Remove previously versioned jars so only one matches the asset glob
          'rm -f target/windtrader-java-*.jar; ' +
          // Copy to the versioned filename for this release
          'cp \"$JAR\" \"target/windtrader-java-${nextRelease.version}.jar\"; ' +
          'ls -lah target | sed -n \"1,160p\"\'',
      },
    ],

    // Attach the versioned jar to the GitHub Release
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

    // Commit changelog back to main
    [
      "@semantic-release/git",
      {
        assets: ["CHANGELOG.md"],
        // IMPORTANT: keep this a normal string (not a JS template literal)
        message: "🔖 Release v${nextRelease.version}\n\n[skip ci]",
      },
    ],
  ],
};
