package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import kage.Age
import kage.Identity
import kage.crypto.x25519.X25519Identity
import java.io.ByteArrayOutputStream
import androidx.core.net.toUri

object WgFeedAge {

    private const val PREFIX = "AGE-SECRET-KEY-"

    /** Build an Identity from a stored full AGE secret key string. */
    fun identityFromSecretKey(secretKey: String?): Identity? {
        val sk = secretKey?.trim().orEmpty()
        if (sk.isBlank()) return null
        if (!sk.startsWith(PREFIX)) return null
        return runCatching { X25519Identity.decode(sk) }.getOrNull()
    }

    /** Extract and normalize the full AGE secret key string from a Setup URL (if present). */
    fun secretKeyFromSetupUrl(url: String): String? {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return null
        val fragment = uri.fragment?.trim().orEmpty()
        if (fragment.isBlank()) return null
        return PREFIX + fragment.uppercase()
    }

    /** Decrypts an armored AGE-encrypted string using the provided Identity. */
    fun decryptArmored(armored: String, identity: Identity): String {
        val inputStream = armored.byteInputStream()
        val outputStream = ByteArrayOutputStream()

        Age.decryptStream(listOf(identity), inputStream, outputStream)

        return outputStream.toString("UTF-8")
    }
}
