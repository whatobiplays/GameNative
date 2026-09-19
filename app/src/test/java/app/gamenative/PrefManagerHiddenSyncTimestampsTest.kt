package app.gamenative

import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.installFakePrefManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefManagerHiddenSyncTimestampsTest {

    @Test
    fun hiddenSourceTimestampsDefaultToZero() = runBlocking {
        installFakePrefManager(FakeDataStore()).use {
            assertEquals(0L, PrefManager.getLastSuccessfulGogComHiddenSync())
            assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
        }
    }

    @Test
    fun hiddenSourceTimestampsArePersistedIndependently() = runBlocking {
        installFakePrefManager(FakeDataStore()).use {
            PrefManager.setLastSuccessfulGogComHiddenSync(11L)
            PrefManager.setLastSuccessfulGalaxyHiddenSync(22L)

            assertEquals(11L, PrefManager.getLastSuccessfulGogComHiddenSync())
            assertEquals(22L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
        }
    }

    @Test
    fun clearingHiddenSourceTimestampsResetsBothSources() = runBlocking {
        installFakePrefManager(FakeDataStore()).use {
            PrefManager.setLastSuccessfulGogComHiddenSync(11L)
            PrefManager.setLastSuccessfulGalaxyHiddenSync(22L)

            PrefManager.clearHiddenSyncTimestamps()

            assertEquals(0L, PrefManager.getLastSuccessfulGogComHiddenSync())
            assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
        }
    }
}
