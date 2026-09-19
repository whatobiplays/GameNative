package app.gamenative.service.gog

import org.junit.Assert.assertEquals
import org.junit.Test

class GogHiddenSyncModelsTest {

    @Test
    fun initializationPolicyRequestsBothWhenNeitherSourceHasSucceeded() {
        assertEquals(
            setOf(GogHiddenSource.GOG_COM, GogHiddenSource.GALAXY),
            GogHiddenSyncPolicy.sourcesNeedingInitialization(0L, 0L),
        )
    }

    @Test
    fun initializationPolicyRequestsOnlyGalaxyWhenWebsiteSourceIsInitialized() {
        assertEquals(
            setOf(GogHiddenSource.GALAXY),
            GogHiddenSyncPolicy.sourcesNeedingInitialization(1L, 0L),
        )
    }

    @Test
    fun initializationPolicyRequestsOnlyGogComWhenGalaxySourceIsInitialized() {
        assertEquals(
            setOf(GogHiddenSource.GOG_COM),
            GogHiddenSyncPolicy.sourcesNeedingInitialization(0L, 1L),
        )
    }

    @Test
    fun initializationPolicyRequestsNothingWhenBothSourcesAreInitialized() {
        assertEquals(
            emptySet<GogHiddenSource>(),
            GogHiddenSyncPolicy.sourcesNeedingInitialization(1L, 1L),
        )
    }
}
