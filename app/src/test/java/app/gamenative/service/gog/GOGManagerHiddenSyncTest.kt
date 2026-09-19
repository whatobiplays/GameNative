package app.gamenative.service.gog

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GOGGame
import app.gamenative.db.PluviaDatabase
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.installFakePrefManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GOGManagerHiddenSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: PluviaDatabase
    private lateinit var manager: GOGManager
    private var prefScope: AutoCloseable? = null

    @After
    fun tearDown() {
        prefScope?.close()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun refreshHiddenStateReconcilesOneSourceAndAdvancesOnlyItsTimestamp() = runBlocking {
        setUp(FakeDataStore())
        database.gogGameDao().upsertPreservingInstallStatus(
            listOf(game(id = "1", gogComHidden = false, galaxyHidden = true)),
        )
        manager.hiddenSourceFetcher = { source ->
            Result.success(GogHiddenSnapshot(source, mapOf("1" to true, "unknown" to true)))
        }

        val result = manager.refreshHiddenState(setOf(GogHiddenSource.GOG_COM))
        val stored = database.gogGameDao().getById("1")!!

        assertEquals(GogHiddenSource.GOG_COM, result.results.keys.single())
        assertEquals(GogHiddenSourceResult.Success(2), result.results[GogHiddenSource.GOG_COM])
        assertTrue(stored.gogComHidden)
        assertTrue(stored.galaxyHidden)
        assertTrue(PrefManager.getLastSuccessfulGogComHiddenSync() > 0L)
        assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
    }

    @Test
    fun validEmptySnapshotPreservesOmittedRowsAndAdvancesTimestamp() = runBlocking {
        setUp(FakeDataStore())
        database.gogGameDao().upsertPreservingInstallStatus(
            listOf(game(id = "1", gogComHidden = true, galaxyHidden = false)),
        )
        manager.hiddenSourceFetcher = { source ->
            Result.success(GogHiddenSnapshot(source, emptyMap()))
        }

        val result = manager.refreshHiddenState(setOf(GogHiddenSource.GOG_COM))

        assertEquals(GogHiddenSourceResult.Success(0), result.results[GogHiddenSource.GOG_COM])
        assertTrue(database.gogGameDao().getById("1")!!.gogComHidden)
        assertTrue(PrefManager.getLastSuccessfulGogComHiddenSync() > 0L)
    }

    @Test
    fun galaxyFailureDoesNotRollBackCommittedGogComSource() = runBlocking {
        setUp(FakeDataStore())
        database.gogGameDao().upsertPreservingInstallStatus(listOf(game(id = "1")))
        val calls = mutableListOf<GogHiddenSource>()
        manager.hiddenSourceFetcher = { source ->
            calls += source
            if (source == GogHiddenSource.GOG_COM) {
                Result.success(GogHiddenSnapshot(source, mapOf("1" to true)))
            } else {
                Result.failure(IllegalStateException("Galaxy unavailable"))
            }
        }

        val result = manager.refreshHiddenState()

        assertEquals(listOf(GogHiddenSource.GOG_COM, GogHiddenSource.GALAXY), calls)
        assertTrue(result.results[GogHiddenSource.GOG_COM] is GogHiddenSourceResult.Success)
        assertTrue(result.results[GogHiddenSource.GALAXY] is GogHiddenSourceResult.Failure)
        assertTrue(database.gogGameDao().getById("1")!!.gogComHidden)
        assertTrue(PrefManager.getLastSuccessfulGogComHiddenSync() > 0L)
        assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
    }

    @Test
    fun emptySourceSelectionDoesNotFetchAnything() = runBlocking {
        setUp(FakeDataStore())
        val calls = AtomicInteger(0)
        manager.hiddenSourceFetcher = { source ->
            calls.incrementAndGet()
            Result.success(GogHiddenSnapshot(source, emptyMap()))
        }

        val result = manager.refreshHiddenState(emptySet())

        assertTrue(result.results.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun gogComFailureDoesNotPreventGalaxyAttempt() = runBlocking {
        setUp(FakeDataStore())
        val calls = mutableListOf<GogHiddenSource>()
        manager.hiddenSourceFetcher = { source ->
            calls += source
            if (source == GogHiddenSource.GOG_COM) {
                Result.failure(IllegalStateException("gog.com unavailable"))
            } else {
                Result.success(GogHiddenSnapshot(source, emptyMap()))
            }
        }

        val result = manager.refreshHiddenState()

        assertEquals(listOf(GogHiddenSource.GOG_COM, GogHiddenSource.GALAXY), calls)
        assertTrue(result.results[GogHiddenSource.GOG_COM] is GogHiddenSourceResult.Failure)
        assertTrue(result.results[GogHiddenSource.GALAXY] is GogHiddenSourceResult.Success)
    }

    @Test
    fun cancellationAfterGogComCommitRetainsCommittedStateAndTimestamp() = runBlocking {
        setUp(FakeDataStore())
        database.gogGameDao().upsertPreservingInstallStatus(listOf(game(id = "1")))
        manager.hiddenSourceFetcher = { source ->
            if (source == GogHiddenSource.GOG_COM) {
                Result.success(GogHiddenSnapshot(source, mapOf("1" to true)))
            } else {
                throw CancellationException("cancelled during Galaxy")
            }
        }

        try {
            manager.refreshHiddenState()
            throw AssertionError("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("cancelled during Galaxy", expected.message)
        }

        assertTrue(database.gogGameDao().getById("1")!!.gogComHidden)
        assertTrue(PrefManager.getLastSuccessfulGogComHiddenSync() > 0L)
        assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
    }

    @Test
    fun timestampFailureRetainsRoomCommitAndLeavesPreviousTimestamp() = runBlocking {
        setUp(FailingDataStore(mutablePreferencesOf(
            longPreferencesKey("lastSuccessfulGogComHiddenSync") to 7L,
        )))
        database.gogGameDao().upsertPreservingInstallStatus(listOf(game(id = "1")))
        manager.hiddenSourceFetcher = { source ->
            Result.success(GogHiddenSnapshot(source, mapOf("1" to true)))
        }

        val result = manager.refreshHiddenState(setOf(GogHiddenSource.GOG_COM))

        assertTrue(result.results[GogHiddenSource.GOG_COM] is GogHiddenSourceResult.Failure)
        assertTrue(database.gogGameDao().getById("1")!!.gogComHidden)
        assertEquals(7L, PrefManager.getLastSuccessfulGogComHiddenSync())
    }

    @Test
    fun clearHiddenStateResetsRetainedRowsAndBothSourceTimestamps() = runBlocking {
        setUp(FakeDataStore())
        database.gogGameDao().upsertPreservingInstallStatus(
            listOf(game(id = "1", gogComHidden = true, galaxyHidden = true)),
        )
        PrefManager.setLastSuccessfulGogComHiddenSync(11L)
        PrefManager.setLastSuccessfulGalaxyHiddenSync(22L)

        manager.clearHiddenState()

        val stored = database.gogGameDao().getById("1")!!
        assertFalse(stored.gogComHidden)
        assertFalse(stored.galaxyHidden)
        assertEquals(0L, PrefManager.getLastSuccessfulGogComHiddenSync())
        assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
    }

    @Test
    fun cancellationPropagatesWithoutBecomingSourceFailure() = runBlocking {
        setUp(FakeDataStore())
        manager.hiddenSourceFetcher = { throw CancellationException("cancelled") }

        try {
            manager.refreshHiddenState(setOf(GogHiddenSource.GOG_COM))
            throw AssertionError("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    @Test
    fun fullSyncRunsHiddenSourcesAfterLibraryFailureWhenAuthenticated() = runBlocking {
        setUp(FakeDataStore())
        val hiddenCalls = AtomicInteger(0)
        manager.libraryRefreshForFullSync = { Result.failure(IllegalStateException("library failed")) }
        manager.credentialsAvailableForFullSync = { true }
        manager.hiddenSourceFetcher = { source ->
            hiddenCalls.incrementAndGet()
            Result.success(GogHiddenSnapshot(source, emptyMap()))
        }

        val result = manager.refreshFullSync(context)

        assertTrue(result.library is GogLibraryRefreshOutcome.Failure)
        assertEquals(2, hiddenCalls.get())
        assertEquals(2, result.hidden.results.size)
    }

    @Test
    fun fullSyncKeepsLibrarySuccessWhenHiddenSourceFails() = runBlocking {
        setUp(FakeDataStore())
        manager.libraryRefreshForFullSync = { Result.success(3) }
        manager.credentialsAvailableForFullSync = { true }
        manager.hiddenSourceFetcher = { source ->
            Result.failure(IllegalStateException("${source.name} failed"))
        }

        val result = manager.refreshFullSync(context)

        assertEquals(GogLibraryRefreshOutcome.Success(3), result.library)
        assertTrue(result.hidden.results.values.all { it is GogHiddenSourceResult.Failure })
    }

    @Test
    fun rowsInsertedByLibraryRefreshAreReconciledByFollowingHiddenRefresh() = runBlocking {
        setUp(FakeDataStore())
        manager.libraryRefreshForFullSync = {
            database.gogGameDao().upsertPreservingInstallStatus(listOf(game(id = "new")))
            Result.success(1)
        }
        manager.credentialsAvailableForFullSync = { true }
        manager.hiddenSourceFetcher = { source ->
            Result.success(GogHiddenSnapshot(source, mapOf("new" to true)))
        }

        val result = manager.refreshFullSync(context)

        assertTrue(result.library is GogLibraryRefreshOutcome.Success)
        assertTrue(database.gogGameDao().getById("new")!!.hidden)
    }

    @Test
    fun backgroundSyncReturnsCompositeAndBackfillsAfterHiddenIssues() = runBlocking {
        setUp(FakeDataStore())
        manager.libraryRefreshForFullSync = { Result.success(3) }
        manager.credentialsAvailableForFullSync = { true }
        manager.hiddenSourceFetcher = { source ->
            Result.failure(IllegalStateException("${source.name} unavailable"))
        }
        var backfillCalls = 0
        manager.backfillVerticalCoversForFullSync = { backfillCalls++ }

        val result = manager.startBackgroundSync(context)

        assertEquals(GogLibraryRefreshOutcome.Success(3), result.library)
        assertTrue(result.hidden.results.values.all { it is GogHiddenSourceResult.Failure })
        assertEquals(1, backfillCalls)
    }

    @Test
    fun fullSyncCommitsSuccessfulHiddenSourceWithoutAdvancingFailedSource() = runBlocking {
        setUp(FakeDataStore())
        database.gogGameDao().upsertPreservingInstallStatus(listOf(game(id = "1")))
        PrefManager.setLastSuccessfulGalaxyHiddenSync(17L)
        manager.libraryRefreshForFullSync = { Result.success(3) }
        manager.credentialsAvailableForFullSync = { true }
        manager.hiddenSourceFetcher = { source ->
            if (source == GogHiddenSource.GOG_COM) {
                Result.success(GogHiddenSnapshot(source, mapOf("1" to true)))
            } else {
                Result.failure(IllegalStateException("Galaxy unavailable"))
            }
        }
        var backfillCalls = 0
        manager.backfillVerticalCoversForFullSync = { backfillCalls++ }

        val result = manager.startBackgroundSync(context)

        assertEquals(GogLibraryRefreshOutcome.Success(3), result.library)
        assertEquals(
            GogHiddenSourceResult.Success(1),
            result.hidden.results[GogHiddenSource.GOG_COM],
        )
        assertTrue(result.hidden.results[GogHiddenSource.GALAXY] is GogHiddenSourceResult.Failure)
        assertTrue(database.gogGameDao().getById("1")!!.gogComHidden)
        assertTrue(PrefManager.getLastSuccessfulGogComHiddenSync() > 0L)
        assertEquals(17L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
        assertEquals(1, backfillCalls)
    }

    @Test
    fun fullSyncDoesNotAttemptHiddenSourcesWhenUnauthenticated() = runBlocking {
        setUp(FakeDataStore())
        val hiddenCalls = AtomicInteger(0)
        manager.libraryRefreshForFullSync = { Result.failure(IllegalStateException("not authenticated")) }
        manager.credentialsAvailableForFullSync = { false }
        manager.hiddenSourceFetcher = { source ->
            hiddenCalls.incrementAndGet()
            Result.success(GogHiddenSnapshot(source, emptyMap()))
        }

        val result = manager.refreshFullSync(context)

        assertEquals(0, hiddenCalls.get())
        assertTrue(result.hidden.results.isEmpty())
    }

    private fun setUp(dataStore: DataStore<Preferences>) {
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = GOGManager(database.gogGameDao(), context)
        prefScope = installFakePrefManager(dataStore)
    }

    private fun game(
        id: String,
        gogComHidden: Boolean = false,
        galaxyHidden: Boolean = false,
    ) = GOGGame(
        id = id,
        title = "Game $id",
        gogComHidden = gogComHidden,
        galaxyHidden = galaxyHidden,
    )

    private class FailingDataStore(
        private val initial: Preferences,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = kotlinx.coroutines.flow.flowOf(initial)

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            throw IllegalStateException("preference write failed")
        }
    }
}
