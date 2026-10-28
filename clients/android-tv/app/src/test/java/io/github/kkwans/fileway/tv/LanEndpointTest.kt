package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.data.LanServiceDiscovery
import org.junit.Assert.*
import org.junit.Test

class LanEndpointTest {
    private val defaults=mapOf("version" to "1","scheme" to "http","path" to "/")
    @Test fun usesResolvedPortAndEncodedBasePath() {
        assertEquals("http://192.168.1.4:49152",LanServiceDiscovery.endpoint("192.168.1.4",49152,defaults))
        assertEquals("https://media.example:9443/my%20files",LanServiceDiscovery.endpoint("192.168.1.4",9443,defaults+mapOf("scheme" to "https","host" to "media.example","path" to "/my%20files")))
        assertEquals("http://[fd00::123]:8000",LanServiceDiscovery.endpoint("fd00::123",8000,defaults))
    }
    @Test fun rejectsCredentialedRedirectedAndUnknownAdvertisements() {
        for(attributes in listOf(defaults+mapOf("host" to "user:pass@server"),defaults+mapOf("version" to "2"),defaults+mapOf("scheme" to "file"),defaults+mapOf("path" to "/?token=x"),defaults+mapOf("path" to "/%2e%2e/private"))) {
            assertNull(LanServiceDiscovery.endpoint("192.168.1.4",8000,attributes))
        }
        assertNull(LanServiceDiscovery.endpoint("192.168.1.4",0,defaults))
    }
}
