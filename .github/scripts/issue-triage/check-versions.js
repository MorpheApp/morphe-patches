// Checks the patches version and app version of a bug report.
// Bug reports for outdated patches, or for an app version that the patches do not list as compatible,
// are closed with a comment. Versions that cannot be recognized are not checked.

const { parseForm } = require('./common.js');

// Pre-releases are allowed if they are one of the newest ones, or were published recently.
const ALLOWED_PRE_RELEASE_COUNT = 4;
const ALLOWED_PRE_RELEASE_DAYS = 4;

// Compatible app versions are read from both branches,
// as a version can be added or removed on one branch before the other.
const BRANCHES = ['main', 'dev'];

const APP_CONSTANTS = {
  YouTube: 'patches/src/main/kotlin/app/morphe/patches/youtube/shared/Constants.kt',
  'YouTube Music': 'patches/src/main/kotlin/app/morphe/patches/music/shared/Constants.kt',
  Reddit: 'patches/src/main/kotlin/app/morphe/patches/reddit/shared/Constants.kt',
};

// App dropdown value to the apps it covers.
const SELECTED_APPS = {
  YouTube: ['YouTube'],
  'YouTube Music': ['YouTube Music'],
  'YouTube and YouTube Music': ['YouTube', 'YouTube Music'],
  Reddit: ['Reddit'],
};

/**
 * Normalizes a patches version such as "v2.4.0-dev.5", "2.4.0 dev 5" or "Morphe Patches (2.3.0)"
 * to "2.4.0-dev.5" or "2.3.0". Returns null if no version is found.
 */
function parsePatchesVersion(text) {
  const match = /(\d+\.\d+\.\d+)(?:[\s-]*dev[\s.-]*(\d+))?/i.exec(text || '');
  if (!match) return null;
  return match[2] ? `${match[1]}-dev.${match[2]}` : match[1];
}

/**
 * Finds an app version such as "21.30.216" or "2026.40.0". Returns null if no version is found.
 */
function parseAppVersion(text) {
  const match = /\d+\.\d+\.\d+/.exec(text || '');
  return match ? match[0] : null;
}

/**
 * Compares patches versions such as "2.3.0" and "2.4.0-dev.5". A pre-release is older than its release.
 */
function compareVersions(a, b) {
  const parse = (version) => {
    const [, major, minor, patch, dev] = /^(\d+)\.(\d+)\.(\d+)(?:-dev\.(\d+))?$/.exec(version);
    return [Number(major), Number(minor), Number(patch), dev === undefined ? Infinity : Number(dev)];
  };
  const x = parse(a);
  const y = parse(b);
  for (let i = 0; i < x.length; i++) {
    if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1;
  }
  return 0;
}

function releaseVersion(release) {
  return release.tag_name.replace(/^v/, '');
}

/**
 * Returns the patches versions that bug reports are accepted for, and all known versions.
 */
function acceptedPatchesVersions(releases, now) {
  const published = releases.filter((release) => !release.draft);
  const accepted = new Set();

  const latestStable = published.find((release) => !release.prerelease);
  if (latestStable) accepted.add(releaseVersion(latestStable));

  const cutoff = now - ALLOWED_PRE_RELEASE_DAYS * 24 * 60 * 60 * 1000;
  published
    .filter((release) => release.prerelease)
    .forEach((release, index) => {
      if (index < ALLOWED_PRE_RELEASE_COUNT || Date.parse(release.published_at) >= cutoff) {
        accepted.add(releaseVersion(release));
      }
    });

  const versions = published.map(releaseVersion).filter((version) => parsePatchesVersion(version) === version);
  return { accepted, known: new Set(versions) };
}

function parseCompatibleVersions(kotlinSource) {
  return [...kotlinSource.matchAll(/\bversion\s*=\s*"([^"]+)"/g)].map((match) => match[1]);
}

async function compatibleAppVersions(github, owner, repo, core) {
  const versions = {};
  for (const [app, path] of Object.entries(APP_CONSTANTS)) {
    versions[app] = new Set();
    for (const ref of BRANCHES) {
      let content;
      try {
        const { data } = await github.rest.repos.getContent({ owner, repo, path, ref });
        content = Buffer.from(data.content, 'base64').toString('utf8');
      } catch (error) {
        if (error.status !== 404) throw error;
        core.warning(`${path} was not found on ${ref}`);
        continue;
      }
      parseCompatibleVersions(content).forEach((version) => versions[app].add(version));
    }
    if (versions[app].size === 0) {
      core.warning(`No compatible versions found for ${app}, its app versions are not checked`);
    }
  }
  return versions;
}

/**
 * Returns the problems found with the versions of a bug report, as comment lines.
 * Exported separately so it can be tested without the GitHub API.
 */
function findVersionProblems(fields, patchesVersions, appVersions) {
  const problems = [];

  const patchesVersion = parsePatchesVersion(fields['Patches version']);
  // Outdated means a release that is not accepted, or older than every accepted release.
  // Other versions are not checked, as they are likely a typo or a value entered in the wrong field.
  if (patchesVersion && !patchesVersions.accepted.has(patchesVersion)) {
    const accepted = [...patchesVersions.accepted].sort(compareVersions);
    const olderThanAccepted = accepted.length > 0 && compareVersions(patchesVersion, accepted[0]) < 0;
    if (patchesVersions.known.has(patchesVersion) || olderThanAccepted) {
      problems.push('You are using outdated patches');
    }
  }

  const selected = SELECTED_APPS[fields['App']];
  const appVersion = parseAppVersion(fields['App version']);
  if (selected && appVersion) {
    const listsKnown = selected.every((app) => appVersions[app] && appVersions[app].size > 0);
    const compatible = selected.some((app) => appVersions[app] && appVersions[app].has(appVersion));
    // A version of another app means the wrong app was selected, which is not checked here.
    const otherApp = Object.keys(appVersions).some(
      (app) => !selected.includes(app) && appVersions[app].has(appVersion)
    );
    if (listsKnown && !compatible && !otherApp) {
      problems.push(`You are patching a non recommended version of ${selected.join(' or ')}`);
    }
  }

  return problems;
}

/**
 * Closes the issue if its versions are not supported.
 * Returns true if the issue was closed.
 */
async function checkVersions({ github, core, owner, repo, issue }) {
  const fields = parseForm(issue.body);
  // Only bug reports have version fields.
  if (!('Patches version' in fields) && !('App version' in fields)) return false;

  const releases = (await github.rest.repos.listReleases({ owner, repo, per_page: 50 })).data;
  const patchesVersions = acceptedPatchesVersions(releases, Date.now());
  const appVersions = await compatibleAppVersions(github, owner, repo, core);

  const problems = findVersionProblems(fields, patchesVersions, appVersions);
  if (problems.length === 0) {
    core.info(`#${issue.number} versions are supported`);
    return false;
  }

  core.info(`#${issue.number}: ${problems.join(', ')}`);
  await github.rest.issues.createComment({
    owner,
    repo,
    issue_number: issue.number,
    body: problems.join('\n\n'),
  });
  await github.rest.issues.update({
    owner,
    repo,
    issue_number: issue.number,
    state: 'closed',
    state_reason: 'not_planned',
  });
  return true;
}

module.exports = {
  checkVersions,
  findVersionProblems,
  acceptedPatchesVersions,
  parsePatchesVersion,
  compareVersions,
  parseAppVersion,
  parseCompatibleVersions,
};
