package service

import java.security.MessageDigest
import java.util.UUID

object RequestHasher {

    fun hash(dealId: UUID, quantity: Int): String {
        val payload = "$dealId:$quantity"

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }
}