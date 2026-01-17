/**
 * semantic-release config for gitmoji-only commits.
 *
 * Expected commit format examples:
 *   ✨ Add semantic validation command
 *   🐛 Fix EPackage registration
 *   💥 Drop legacy CLI flags
 *   📝 Update README
 *   🔧 CI tweaks
 *
 * The first token (emoji) is treated as the "type".
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
          { type: "⚙️", release: "patch" }
        ],

        // If you want to allow "BREAKING CHANGE:" in body to force major bumps:
        // (This works even with gitmoji-only headers.)
        // noteKeywords: ["BREAKING CHANGE", "BREAKING CHANGES", "BREAKING"]
      }
    ],

    [
      "@semantic-release/release-notes-generator",
      {
        preset: false,
        parserOpts,
        writerOpts: {
          transform: (commit, context) => {
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

            // IMPORTANT: do not mutate `commit` (it may be immutable)
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
        }
      }
    ],

    // Optional but recommended: keep a changelog in-repo
    ["@semantic-release/changelog", { changelogFile: "CHANGELOG.md" }],

    // Create GitHub Release (semantic-release handles notes/tagging)
    "@semantic-release/github",

    // Commit the changelog bump back to main (no version bump in pom.xml needed)
    [
      "@semantic-release/git",
      {
        assets: ["CHANGELOG.md"],
        message: "🔖 Release v${nextRelease.version}\n\n[skip ci]"
      }
    ]
  ]
};
