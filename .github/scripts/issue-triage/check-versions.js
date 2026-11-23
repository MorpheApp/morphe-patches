// Checks the patches version and app version of a bug report.
// Bug reports for outdated patches, for an app version that the patches do not list as compatible,
// or with a missing or invalid version are closed with a comment by apply.js, after the duplicate check.

const { parseForm } = require('./common.js');

// A stable version must be the latest stable release.
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
 * Returns the latest stable patches version, the pre-releases that bug reports are accepted for,
 * and all known versions.
 */
function acceptedPatchesVersions(releases, now) {
  const published = releases.filter((release) => !release.draft);

  const latestStable = published.find((release) => !release.prerelease);

  const cutoff = now - ALLOWED_PRE_RELEASE_DAYS * 24 * 60 * 60 * 1000;
  const preReleases = new Set(
    published
      .filter((release) => release.prerelease)
      .filter((release, index) => index < ALLOWED_PRE_RELEASE_COUNT || Date.parse(release.published_at) >= cutoff)
      .map(releaseVersion)
  );

  const versions = published.map(releaseVersion).filter((version) => parsePatchesVersion(version) === version);
  return {
    latestStable: latestStable ? releaseVersion(latestStable) : null,
    preReleases,
    known: new Set(versions),
  };
}

/**
 * Returns "ok", "outdated" or "invalid" for a patches version.
 * A stable version must be the latest stable release. A pre-release must be one of the accepted pre-releases.
 */
function patchesVersionStatus(version, patchesVersions) {
  if (!version) return 'invalid';

  const { latestStable, preReleases, known } = patchesVersions;
  if (!latestStable) return 'ok';

  if (version.includes('-dev.')) {
    if (preReleases.has(version)) return 'ok';
    const oldest = [...preReleases].sort(compareVersions)[0] || latestStable;
    if (known.has(version) || compareVersions(version, oldest) < 0) return 'outdated';
    return 'invalid';
  }

  if (version === latestStable) return 'ok';
  if (known.has(version) || compareVersions(version, latestStable) < 0) return 'outdated';
  return 'invalid';
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
  let invalid = false;

  const selected = SELECTED_APPS[fields['App']];
  const appName = selected ? selected.join(' or ') : 'the app';

  const patchesStatus = patchesVersionStatus(parsePatchesVersion(fields['Patches version']), patchesVersions);
  if (patchesStatus === 'outdated') problems.push('You are using outdated patches');
  if (patchesStatus === 'invalid') invalid = true;

  const appVersion = parseAppVersion(fields['App version']);
  if (!appVersion) {
    invalid = true;
  } else if (selected) {
    const listsKnown = selected.every((app) => appVersions[app] && appVersions[app].size > 0);
    const compatible = selected.some((app) => appVersions[app] && appVersions[app].has(appVersion));
    // A version of another app means the version or the selected app is wrong.
    const otherApp = Object.keys(appVersions).some(
      (app) => !selected.includes(app) && appVersions[app].has(appVersion)
    );
    if (listsKnown && !compatible) {
      if (otherApp) {
        invalid = true;
      } else {
        problems.push(`You are patching a non recommended version of ${appName}`);
      }
    }
  }

  if (invalid) {
    problems.push(
      `Please include all requested information, including the version of ${appName} you patched ` +
        `and the patches version as shown in ${selected ? appName : 'the app'} > Settings > Morphe > About`
    );
  }

  return problems;
}

/**
 * Returns true if the issue body is a bug report with version fields.
 */
function hasVersionFields(body) {
  const fields = parseForm(body);
  return 'Patches version' in fields || 'App version' in fields;
}

/**
 * Returns the problems found with the versions of the issue, as comment lines.
 * The list is empty if the versions are supported, or if the issue is not a bug report.
 */
async function checkVersions({ github, core, owner, repo, issue }) {
  // Only bug reports have version fields.
  if (!hasVersionFields(issue.body)) return [];
  const fields = parseForm(issue.body);

  const releases = (await github.rest.repos.listReleases({ owner, repo, per_page: 50 })).data;
  const patchesVersions = acceptedPatchesVersions(releases, Date.now());
  const appVersions = await compatibleAppVersions(github, owner, repo, core);

  const problems = findVersionProblems(fields, patchesVersions, appVersions);
  if (problems.length === 0) {
    core.info(`#${issue.number} versions are supported`);
  } else {
    core.info(`#${issue.number}: ${problems.join(', ')}`);
  }
  return problems;
}

module.exports = {
  checkVersions,
  hasVersionFields,
  findVersionProblems,
  acceptedPatchesVersions,
  parsePatchesVersion,
  patchesVersionStatus,
  compareVersions,
  parseAppVersion,
  parseCompatibleVersions,
};
