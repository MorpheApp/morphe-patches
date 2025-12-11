const fs = require('fs');
const path = require('path');

// 1. Receive the next version and release notes (description) as arguments.
const [nextVersion, nextDescription] = process.argv.slice(2);

if (!nextVersion) {
  console.error("Error: Next release version not provided.");
  process.exit(1);
}

// Helper function to return the current timestamp in yyyy-mm-ddTHH:MM:SS format.
function getTimestamp() {
  // ISOString format is 2025-12-09T18:28:18.000Z, so we slice off milliseconds and Z.
  return new Date().toISOString().slice(0, 19);
}

// Helper function to load a JSON file, apply an update function, and save it.
function updateJsonFile(filePath, updateFn) {
  const fullPath = path.join(__dirname, filePath);
  
  try {
    const data = fs.readFileSync(fullPath, 'utf8');
    let jsonContent = JSON.parse(data);

    // Apply the update function.
    jsonContent = updateFn(jsonContent);

    // Overwrite the file (using 2 spaces indentation for readability).
    fs.writeFileSync(fullPath, JSON.stringify(jsonContent, null, 2) + '\n');
    console.log(`✅ ${filePath} updated successfully.`);

  } catch (error) {
    console.error(`❌ Failed to update ${filePath}:`, error.message);
    process.exit(1);
  }
}

// =========================================================
// 1. Update patches-list.json
// =========================================================
console.log(`\n--- Updating patches-list.json (Version: v${nextVersion}) ---`);
updateJsonFile('patches-list.json', (bundle) => {
  const newVersionTag = `v${nextVersion}`;
  console.log(`  'version': ${bundle.version} -> ${newVersionTag}`);
  bundle.version = newVersionTag;
  return bundle;
});

// =========================================================
// 2. Update patches-bundle.json
// =========================================================
console.log(`\n--- Updating bundle.json (Version: v${nextVersion}) ---`);
updateJsonFile('patches-bundle.json', (release) => {
  const timestamp = getTimestamp();
  
  // The current version string stored in patches-bundle.json (used as the base for URL replacement).
  const oldVersionInUrl = release.version.replace('v', '');
  const newVersionInUrl = nextVersion;

  // Function to replace version strings within the download URLs.
  const replaceUrlVersion = (url) => {
    if (!url) return url;
    // 1. Replace /v{OLD_VERSION}/ -> /v{NEW_VERSION}/.
    let newUrl = url.replace(`/v${oldVersionInUrl}/`, `/v${newVersionInUrl}/`);
    // 2. Replace -{OLD_VERSION}. -> -{NEW_VERSION}.
    newUrl = newUrl.replace(`-${oldVersionInUrl}.`, `-${newVersionInUrl}.`);
    return newUrl;
  };
  
  // 1. Update 'created_at' (current time).
  console.log(`  'created_at': ${timestamp}`);
  release.created_at = timestamp;

  // 2. Update 'description' (${nextRelease.notes}).
  console.log(`  'description' (Release Notes) updated`);
  release.description = nextDescription || ""; // Use empty string if release notes are not provided

  // 3. Update 'download_url'.
  release.download_url = replaceUrlVersion(release.download_url);
  
  // 4. Update 'signature_download_url'.
  release.signature_download_url = replaceUrlVersion(release.signature_download_url);
  
  // 5. Update 'version' (v${nextRelease.version}).
  const newVersionTag = `v${nextVersion}`;
  console.log(`  'version': ${release.version} -> ${newVersionTag}`);
  release.version = newVersionTag;
  
  return release;
});