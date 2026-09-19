package app.gamenative.service.gog

import android.app.Application
import android.content.Context
import app.gamenative.data.GOGCredentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GOGApiClientHiddenSyncTest {

    private lateinit var server: MockWebServer
    private val context: Context = Application()
    private val credentials = GOGCredentials(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        userId = "user-123",
        username = "Test User",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        GOGApiClient.hiddenCredentialsProvider = { Result.success(credentials) }
    }

    @After
    fun tearDown() {
        GOGApiClient.hiddenEndpoints = GogHiddenEndpoints()
        GOGApiClient.hiddenCredentialsProvider = { context ->
            GOGAuthManager.getStoredCredentials(context)
        }
        server.shutdown()
    }

    @Test
    fun gogComUsesUnfilteredPagesAndRetainsBothBooleanObservations() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(primaryPath = "/embed")
        server.enqueue(MockResponse().setBody(
            """{"totalPages":2,"products":[{"id":"1","isHidden":true},{"id":"2","isHidden":false}]}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"totalPages":2,"products":[{"id":"3","isHidden":true}]}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GOG_COM)

        assertTrue(result.isSuccess)
        assertEquals(
            mapOf("1" to true, "2" to false, "3" to true),
            result.getOrThrow().observations,
        )
        val firstRequest = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("/embed/account/getFilteredProducts", firstRequest!!.requestUrl!!.encodedPath)
        assertEquals("1", firstRequest.requestUrl!!.queryParameter("mediaType"))
        assertEquals("1", firstRequest.requestUrl!!.queryParameter("page"))
        assertEquals(null, firstRequest.requestUrl!!.queryParameter("hiddenFlag"))
        assertEquals("Bearer access-token", firstRequest.getHeader("Authorization"))
    }

    @Test
    fun validEmptyGogComPrimarySnapshotDoesNotCallFallback() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(primaryPath = "/embed", fallbackPath = "/www")
        server.enqueue(MockResponse().setBody("""{"totalPages":0,"products":[]}"""))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GOG_COM)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().observations.isEmpty())
        assertEquals(1, server.requestCount)
        assertEquals("/embed/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
    }

    @Test
    fun gogComFailureRetriesTheEntireSnapshotOnFallbackWithoutMerging() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(primaryPath = "/embed", fallbackPath = "/www")
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(
            """{"totalPages":2,"products":[{"id":"fallback-1","isHidden":false}]}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"totalPages":2,"products":[{"id":"fallback-2","isHidden":true}]}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GOG_COM)

        assertTrue(result.isSuccess)
        assertEquals(
            mapOf("fallback-1" to false, "fallback-2" to true),
            result.getOrThrow().observations,
        )
        assertEquals(3, server.requestCount)
        assertEquals("/embed/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
        assertEquals("/www/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
        assertEquals("/www/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
    }

    @Test
    fun gogComIntermediatePrimaryFailureRestartsFallbackWithoutLeakingPrimaryObservations() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(primaryPath = "/embed", fallbackPath = "/www")
        server.enqueue(MockResponse().setBody(
            """{"totalPages":2,"products":[{"id":"primary-only","isHidden":true}]}""",
        ))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(
            """{"totalPages":1,"products":[{"id":"fallback-only","isHidden":false}]}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GOG_COM)

        assertTrue(result.isSuccess)
        assertEquals(mapOf("fallback-only" to false), result.getOrThrow().observations)
        assertEquals(3, server.requestCount)
        assertEquals("/embed/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
        assertEquals("/embed/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
        assertEquals("/www/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
    }

    @Test
    fun gogComPrimaryParseFailureRestartsWholeSnapshotOnFallback() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(primaryPath = "/embed", fallbackPath = "/www")
        server.enqueue(MockResponse().setBody("not-json"))
        server.enqueue(MockResponse().setBody(
            """{"totalPages":1,"products":[{"id":"fallback-only","isHidden":true}]}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GOG_COM)

        assertTrue(result.isSuccess)
        assertEquals(mapOf("fallback-only" to true), result.getOrThrow().observations)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun gogComConflictingDuplicateAcrossPagesRestartsWholeSnapshotOnFallback() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(primaryPath = "/embed", fallbackPath = "/www")
        server.enqueue(MockResponse().setBody(
            """{"totalPages":2,"products":[{"id":"1","isHidden":true}]}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"totalPages":2,"products":[{"id":"1","isHidden":false}]}""",
        ))
        // The fallback response must be a fresh snapshot: the conflicting primary observation
        // must not be merged into it.
        server.enqueue(MockResponse().setBody(
            """{"totalPages":1,"products":[{"id":"fallback-only","isHidden":true}]}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GOG_COM)

        assertTrue(result.isSuccess)
        assertEquals(mapOf("fallback-only" to true), result.getOrThrow().observations)
        assertEquals(3, server.requestCount)
        assertEquals("/embed/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
        assertEquals("/embed/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
        assertEquals("/www/account/getFilteredProducts", server.takeRequest()!!.requestUrl!!.encodedPath)
    }

    @Test
    fun gogComChangingTotalPagesFailsTheWholeAttempt() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(primaryPath = "/embed", fallbackPath = "/www")
        server.enqueue(MockResponse().setBody(
            """{"totalPages":2,"products":[{"id":"1","isHidden":true}]}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"totalPages":3,"products":[{"id":"2","isHidden":false}]}""",
        ))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GOG_COM)

        assertFalse(result.isSuccess)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun galaxyUsesBearerAuthCursorPaginationAndRawItemCount() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(galaxyPath = "/galaxy")
        server.enqueue(MockResponse().setBody(
            """{"items":[
                {"platform_id":"gog","owned":true,"external_id":"1","hidden":true},
                {"platform_id":"steam","owned":true,"external_id":"steam-1"}
            ],"next_page_token":"cursor-1","total_count":3}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"items":[
                {"platform_id":"gog","owned":true,"external_id":"2","hidden":false}
            ],"next_page_token":"","total_count":3}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GALAXY)

        assertTrue(result.isSuccess)
        assertEquals(mapOf("1" to true, "2" to false), result.getOrThrow().observations)
        val firstRequest = server.takeRequest()
        assertEquals("/galaxy/users/user-123/releases", firstRequest.requestUrl!!.encodedPath)
        assertEquals(null, firstRequest.requestUrl!!.queryParameter("page_token"))
        assertEquals("Bearer access-token", firstRequest.getHeader("Authorization"))
        val secondRequest = server.takeRequest()
        assertEquals("cursor-1", secondRequest.requestUrl!!.queryParameter("page_token"))
    }

    @Test
    fun galaxyRepeatedCursorFailsTheSnapshot() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(galaxyPath = "/galaxy")
        server.enqueue(MockResponse().setBody(
            """{"items":[],"next_page_token":"loop","total_count":0}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"items":[],"next_page_token":"loop","total_count":0}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GALAXY)

        assertFalse(result.isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun galaxyCountMismatchFailsTheSnapshot() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(galaxyPath = "/galaxy")
        server.enqueue(MockResponse().setBody(
            """{"items":[{"platform_id":"steam","owned":true,"external_id":"steam-1"}],"total_count":2}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GALAXY)

        assertFalse(result.isSuccess)
    }

    @Test
    fun galaxyChangingTotalCountFailsAcrossPages() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(galaxyPath = "/galaxy")
        server.enqueue(MockResponse().setBody(
            """{"items":[],"next_page_token":"cursor-1","total_count":2}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"items":[],"next_page_token":"","total_count":3}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GALAXY)

        assertFalse(result.isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun galaxyConflictingIdentityAcrossPagesFailsTheSnapshot() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(galaxyPath = "/galaxy")
        server.enqueue(MockResponse().setBody(
            """{"items":[
                {"platform_id":"gog","owned":true,"external_id":"1","hidden":true}
            ],"next_page_token":"cursor-1","total_count":2}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"items":[
                {"platform_id":"gog","owned":true,"external_id":"1","hidden":false}
            ],"next_page_token":"","total_count":2}""",
        ))

        val result = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GALAXY)

        assertFalse(result.isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun missingCredentialsAndGalaxyUserIdFailBeforeNetworkAccess() = runBlocking {
        GOGApiClient.hiddenEndpoints = endpoints(galaxyPath = "/galaxy")
        GOGApiClient.hiddenCredentialsProvider = { Result.failure(IllegalStateException("missing credentials")) }

        val missingCredentials = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GALAXY)

        assertFalse(missingCredentials.isSuccess)
        assertEquals(0, server.requestCount)

        GOGApiClient.hiddenCredentialsProvider = { Result.success(credentials.copy(userId = "")) }
        val missingUserId = GOGApiClient.fetchHiddenSnapshot(context, GogHiddenSource.GALAXY)

        assertFalse(missingUserId.isSuccess)
        assertEquals(0, server.requestCount)
    }

    private fun endpoints(
        primaryPath: String = "/embed",
        fallbackPath: String = "/www",
        galaxyPath: String = "/galaxy",
    ): GogHiddenEndpoints = GogHiddenEndpoints(
        gogComPrimaryBaseUrl = server.url(primaryPath).toString().trimEnd('/'),
        gogComFallbackBaseUrl = server.url(fallbackPath).toString().trimEnd('/'),
        galaxyBaseUrl = server.url(galaxyPath).toString().trimEnd('/'),
    )
}
