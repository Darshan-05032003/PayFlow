package com.darshan.payflow.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Authenticated encryption for sensitive local data. Output is `iv || ciphertext || tag`. */
interface CryptoBox {
    fun encrypt(plain: ByteArray, aad: ByteArray? = null): ByteArray
    fun decrypt(cipher: ByteArray, aad: ByteArray? = null): ByteArray
    fun encryptString(plain: String, aad: ByteArray? = null): String = java.util.Base64.getEncoder().encodeToString(encrypt(plain.toByteArray(Charsets.UTF_8), aad))
    fun decryptString(cipher: String, aad: ByteArray? = null): String = decrypt(java.util.Base64.getDecoder().decode(cipher), aad).toString(Charsets.UTF_8)
}

/** AES-256-GCM with a fresh random 96-bit IV per message and a 128-bit tag. The key comes from [keyProvider]. */
class AesGcmCipher(private val keyProvider: () -> SecretKey, private val random: SecureRandom = SecureRandom()) : CryptoBox {
    override fun encrypt(plain: ByteArray, aad: ByteArray?): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BYTES * 8, iv))
        aad?.let(cipher::updateAAD)
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(cipher: ByteArray, aad: ByteArray?): ByteArray {
        require(cipher.size >= IV_BYTES + TAG_BYTES) { "ciphertext too short" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BYTES * 8, cipher, 0, IV_BYTES))
        aad?.let(c::updateAAD)
        return c.doFinal(cipher, IV_BYTES, cipher.size - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/** JVM/test implementation: a random AES-256 key held in memory. Same cipher code as production, different key source. */
class InMemoryCryptoBox : CryptoBox {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCipher({ key })
    override fun encrypt(plain: ByteArray, aad: ByteArray?): ByteArray = cipher.encrypt(plain, aad)
    override fun decrypt(cipher: ByteArray, aad: ByteArray?): ByteArray = this.cipher.decrypt(cipher, aad)
}

/**
 * Production implementation: the AES-256 key lives in the Android Keystore (hardware-backed where available), is generated on
 * first use and never leaves the secure hardware; only encrypt/decrypt operations cross the boundary. Robolectric has no
 * AndroidKeyStore provider, so this class is exercised only on a device (not run: no device); [AesGcmCipher] is tested on the JVM.
 */
@Singleton
class KeystoreCryptoBox @Inject constructor() : CryptoBox {
    private val cipher = AesGcmCipher(::key)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false) // we supply our own random IV per message
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray, aad: ByteArray?): ByteArray = cipher.encrypt(plain, aad)
    override fun decrypt(cipher: ByteArray, aad: ByteArray?): ByteArray = this.cipher.decrypt(cipher, aad)

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "payflow.secure.v1"
    }
}
