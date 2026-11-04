
package security

import com.nnomm.errors.AppError
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookSignatureVerifier(
    private val secret: String = System.getenv("WEBHOOK_SECRET")
        ?: throw IllegalStateException("WEBHOOK_SECRET is not configured")
) {

    fun verify(
        signatureHeader: String?,
        rawBody: ByteArray,
        now: Instant = Instant.now()
    ) {
        // 1. Validate the signature header format.
        val match = HEADER_REGEX.matchEntire(signatureHeader ?: "")
            ?: unauthorized()

        val timestampText = match.groupValues[1]
        val suppliedSignature = match.groupValues[2]

        // 2. Parse and validate the timestamp.
        val timestamp = timestampText.toLongOrNull()
            ?: unauthorized()

        if (
    timestamp < now.epochSecond - MAX_TIMESTAMP_DIFFERENCE_SECONDS ||
    timestamp > now.epochSecond + MAX_TIMESTAMP_DIFFERENCE_SECONDS
) {
    unauthorized()
}

        // 3. Construct the exact message: timestamp + "." + raw body.
        val prefix = "$timestampText.".toByteArray(StandardCharsets.UTF_8)
        val signedMessage = prefix + rawBody

        // 4. Calculate HMAC-SHA256.
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(
            secret.toByteArray(StandardCharsets.UTF_8),
            "HmacSHA256"
        )

        mac.init(key)
        val expectedSignature = mac.doFinal(signedMessage)

        // 5. Decode the supplied hexadecimal signature.
        val actualSignature = try {
            HexFormat.of().parseHex(suppliedSignature)
        } catch (exception: IllegalArgumentException) {
            unauthorized()
        }

        // 6. Compare in constant time.
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            unauthorized()
        }
    }

    private fun unauthorized(): Nothing {
        throw AppError(
            HttpStatusCode.Unauthorized,
            "UNAUTHENTICATED",
            "Invalid payment webhook signature"
        )
    }

    companion object {
        private const val MAX_TIMESTAMP_DIFFERENCE_SECONDS = 300L

        private val HEADER_REGEX =
            Regex("""^t=(\d+),v1=([0-9a-fA-F]{64})$""")
    }
}
