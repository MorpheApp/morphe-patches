/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

/*!
 * Player JS caching wrapper for yt.solver.core.js
 *
 * This wrapper maintains player JS state in memory to avoid:
 * 1. Repeated JSON serialization/deserialization of large player JS
 * 2. Repeated V8 parsing and bytecode generation for the same player
 * 3. Repeated preprocessing of player JS by jsc()
 */
var _playerCache = {
    playerHash: null,
    preprocessedPlayer: null,
    isPreprocessed: false
};

/**
 * Set player JS code with a hash for cache validation.
 * Call this before calling jscw() to ensure the player is available.
 *
 * @param {string} playerJS - The raw player JavaScript code
 * @param {string} playerHash - A hash of the player JS for cache validation
 */
function setPlayer(playerJS, playerHash) {
    if (_playerCache.playerHash !== playerHash) {
        _playerCache.playerHash = playerHash;
        _playerCache.preprocessedPlayer = playerJS;
        _playerCache.isPreprocessed = false;
    }
}

/**
 * Set preprocessed player JS code.
 * Use this when you already have preprocessed player JS from cache.
 *
 * @param {string} preprocessedPlayer - The preprocessed player JavaScript code
 * @param {string} playerHash - A hash of the original player JS for cache validation
 */
function setPreprocessedPlayer(preprocessedPlayer, playerHash) {
    if (_playerCache.playerHash !== playerHash) {
        _playerCache.playerHash = playerHash;
        _playerCache.preprocessedPlayer = preprocessedPlayer;
        _playerCache.isPreprocessed = true;
    }
}

/**
 * Clear the player cache. Call this when switching players or cleaning up.
 */
function clearPlayerCache() {
    _playerCache.playerHash = null;
    _playerCache.preprocessedPlayer = null;
    _playerCache.isPreprocessed = false;
}

/**
 * Get the current player hash, or null if no player is loaded.
 *
 * @returns {string|null} The current player hash
 */
function getPlayerHash() {
    return _playerCache.playerHash;
}

/**
 * Check if the player with the given hash is already loaded and preprocessed.
 *
 * @param {string} playerHash - The hash to check
 * @returns {boolean} True if the player is loaded and preprocessed
 */
function isPlayerReady(playerHash) {
    return _playerCache.playerHash === playerHash && _playerCache.isPreprocessed;
}

/**
 * Wrapped jsc() function that uses the cached player JS.
 *
 * Input format:
 * {
 *   "requests": [
 *     {"type": "n" | "sig", "challenges": ["challenge1", "challenge2", ...]}
 *   ],
 *   "output_preprocessed": boolean (optional, defaults to true if player not preprocessed)
 * }
 *
 * @param {object} input - The input object containing requests
 * @returns {object} The solver output with responses and optionally preprocessed_player
 */
function jscw(input) {
    if (!_playerCache.preprocessedPlayer) {
        return {
            type: "error",
            error: "No player loaded. Call setPlayer() or setPreprocessedPlayer() first."
        };
    }

    // Build the input for the underlying jsc() function
    var jscInput;
    if (_playerCache.isPreprocessed) {
        jscInput = {
            type: "preprocessed",
            preprocessed_player: _playerCache.preprocessedPlayer,
            requests: input.requests
        };
    } else {
        jscInput = {
            type: "player",
            player: _playerCache.preprocessedPlayer,
            requests: input.requests,
            output_preprocessed: true
        };
    }

    // Call the underlying jsc() function
    var output = jsc(jscInput);

    // If we got preprocessed player back, cache it
    if (output.preprocessed_player && !_playerCache.isPreprocessed) {
        _playerCache.preprocessedPlayer = output.preprocessed_player;
        _playerCache.isPreprocessed = true;
    }

    return output;
}

/**
 * Get the preprocessed player if available.
 * This can be used by Java code to cache the preprocessed player.
 *
 * @returns {string|null} The preprocessed player JS, or null if not available
 */
function getPreprocessedPlayer() {
    if (_playerCache.isPreprocessed) {
        return _playerCache.preprocessedPlayer;
    }
    return null;
}

// Export for testing
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        setPlayer: setPlayer,
        setPreprocessedPlayer: setPreprocessedPlayer,
        clearPlayerCache: clearPlayerCache,
        getPlayerHash: getPlayerHash,
        isPlayerReady: isPlayerReady,
        jscw: jscw,
        getPreprocessedPlayer: getPreprocessedPlayer
    };
}
