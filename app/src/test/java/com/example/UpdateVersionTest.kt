package com.example

import com.example.engine.updater.GitHubUpdateManager
import org.junit.Assert.*
import org.junit.Test

/** The previous updater compared tags as strings ("1.0.9" vs "1.0.10") and always reported an update. */
class UpdateVersionTest {
    @Test
    fun comparesNumerically() {
        assertTrue(GitHubUpdateManager.isVersionNewer("1.1.10", "1.1.9"))
        assertFalse(GitHubUpdateManager.isVersionNewer("1.1.9", "1.1.10"))
        assertFalse(GitHubUpdateManager.isVersionNewer("1.1.10", "1.1.10"))
    }

    @Test
    fun ignoresPrefixAndSuffix() {
        assertTrue(GitHubUpdateManager.isVersionNewer("v1.2.0", "1.1.99"))
        assertFalse(GitHubUpdateManager.isVersionNewer("1.1.5", "1.1.5-debug"))
        assertTrue(GitHubUpdateManager.isVersionNewer("1.2", "1.1.9"))
    }

    @Test
    fun blankIsNeverNewer() {
        assertFalse(GitHubUpdateManager.isVersionNewer("", "1.0"))
    }
}
