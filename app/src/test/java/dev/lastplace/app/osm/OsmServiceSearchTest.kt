package dev.lastplace.app.osm

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class OsmServiceSearchTest {
    @Test
    fun serviceFailureIsNotReportedAsEmptySearchResults() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Unavailable")
                .body("Unavailable".toResponseBody())
                .build()
        }.build()
        try {
            OsmService(client).searchStreets("Ynglingagatan", null)
            fail("Expected an explicit service failure, not an empty list")
        } catch (e: OsmRequestException) {
            assertTrue(e.message!!.contains("503"))
        }
    }

    @Test
    fun networkFailureIsNotReportedAsEmptySearchResults() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("Offline") }.build()
        try {
            OsmService(client).searchStreets("Ynglingagatan", null)
            fail("Expected an explicit network failure, not an empty list")
        } catch (e: OsmRequestException) {
            assertTrue(e.message!!.contains("Network error"))
        }
    }
}
