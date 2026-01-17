/**
 * semantic-release config for gitmoji-only commits + versioned jar asset.
 *
 * Key behavior:
 * - Maven still builds target/windtrader-java-<pomVersion>.jar (e.g. 0.1.0)
 * - During release, we rename that built jar so it won't be uploaded
 * - Then we create exactly ONE asset: target/windtrader-java-<nextRelease.version>.jar
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

            // Do NOT mutate `commit` (it may be immutable)
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

    // Build a versioned jar for the GitHub Release and ensure ONLY that one matches the glob.
    [
      "@semantic-release/exec",
      {
        // "prepare" runs after nextRelease.version is known, before publishing.
        prepareCmd:
          "bash -lc " +
          `"set -e; ` +
          // pick the runnable shaded jar (exclude shade's original- jar)
          `JAR=\\$(ls -1 target/*.jar 2>/dev/null | grep -v '^target/original-' | head -n 1); ` +
          `if [ -z \\\"\\$JAR\\\" ]; then echo 'No jar found in target/. Did mvn package run?'; ls -lah target || true; exit 1; fi; ` +
          `echo Using built jar: \\$JAR; ` +
          // rename the built jar so it doesn't match windtrader-java-*.jar anymore
          `mv \\\"\\$JAR\\\" target/_built.jar; ` +
          // create the *one* release asset we want
          `cp target/_built.jar \\\"target/windtrader-java-\\${nextRelease.version}.jar\\\"; ` +
          `ls -lh target/_built.jar target/windtrader-java-\\${nextRelease.version}.jar"`,
      },
    ],

    ["@semantic-release/changelog", { changelogFile: "CHANGELOG.md" }],

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
        message: "🔖 Release v${nextRelease.version}\n\n[skip ci]",
      },
    ],
  ],
};
