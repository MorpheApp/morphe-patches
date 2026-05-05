module.exports = {
  branches: ['main'],
  repositoryUrl: 'https://github.com/atanuroy22/morphe-patches.git',
  plugins: [
    // Analyze commits to determine version bump
    [
      '@semantic-release/commit-analyzer',
      {
        preset: 'angular',
        releaseRules: [
          { type: 'feat', release: 'minor' },
          { type: 'fix', release: 'patch' },
          { type: 'perf', release: 'patch' },
          { type: 'revert', release: 'patch' },
          { type: 'docs', release: false },
          { type: 'style', release: false },
          { type: 'refactor', release: false },
          { type: 'test', release: false },
          { type: 'ci', release: false },
          { type: 'chore', release: false },
          { type: 'build', release: false },
        ],
      },
    ],
    // Generate changelog
    [
      '@semantic-release/release-notes-generator',
      {
        preset: 'angular',
      },
    ],
    // Prepare step - skip for forks without GitHub Packages access
    [
      '@semantic-release/exec',
      {
        // Skip Gradle build verification - forks may not have GitHub Packages access
        verifyConditionsCmd: 'echo "Preparing release for fork..."',
        prepareCmd: 'echo "Skipping Gradle build for fork release" || true',
        publishCmd: 'echo "Publishing to GitHub..." || true',
      },
    ],
    // Publish release notes to GitHub
    [
      '@semantic-release/github',
      {
        successComment: false,
        failComment: false,
        releasedLabels: false,
      },
    ],
    // Create git tag and commit
    [
      '@semantic-release/git',
      {
        assets: ['CHANGELOG.md', 'package.json'],
        message: 'chore(release): ${nextRelease.version} [skip ci]\n\n${nextRelease.notes}',
      },
    ],
    // Backmerge into dev branch if needed
    [
      '@cleyrop-org/semantic-release-backmerge',
      {
        backmergeStrategy: 'ff-only',
        backmergebranches: [],
      },
    ],
  ],
};
