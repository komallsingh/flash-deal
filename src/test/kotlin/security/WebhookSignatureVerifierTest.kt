
package security

import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class WebhookSignatureVerifierTest {

    private val secret = "test-webhook-secret-for-unit-tests"
    private val verifier = WebhookSignatureVerifier(secret)

    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val body = """{"event_id":"evt_123","type":"payment.succeeded"}"""
        .toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `accepts valid signature`() {
        val header = createSignatureHeader(body, now.epochSecond)

        verifier.verify(header, body, now)
    }

    @Test
    fun `rejects incorrect signature`() {
        val header = createSignatureHeader(body, now.epochSecond)
            .replace(Regex("v1=[0-9a-f]+"), "v1=${"0".repeat(64)}")

        assertFails {
            verifier.verify(header, body, now)
        }
    }

    @Test
    fun `rejects missing signature header`() {
        assertFails {
            verifier.verify(null, body, now)
        }
    }

    @Test
    fun `rejects malformed signature header`() {
        assertFails {
            verifier.verify("invalid-signature", body, now)
        }
    }

    @Test
    fun `rejects stale timestamp`() {
        val oldTimestamp = now.epochSecond - 301
        val header = createSignatureHeader(body, oldTimestamp)

        assertFails {
            verifier.verify(header, body, now)
        }
    }

    @Test
    fun `rejects timestamp too far in future`() {
        val futureTimestamp = now.epochSecond + 301
        val header = createSignatureHeader(body, futureTimestamp)

        assertFails {
            verifier.verify(header, body, now)
        }
    }

    @Test
    fun `accepts timestamp exactly 300 seconds away`() {
        val timestamp = now.epochSecond - 300
        val header = createSignatureHeader(body, timestamp)

        verifier.verify(header, body, now)
    }

    private fun createSignatureHeader(
        body: ByteArray,
        timestamp: Long
    ): String {
        val message = "$timestamp.".toByteArray(StandardCharsets.UTF_8) + body

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                secret.toByteArray(StandardCharsets.UTF_8),
                "HmacSHA256"
            )
        )

        val signature = mac.doFinal(message)
            .joinToString("") { "%02x".format(it) }

        return "t=$timestamp,v1=$signature"
    }
}
