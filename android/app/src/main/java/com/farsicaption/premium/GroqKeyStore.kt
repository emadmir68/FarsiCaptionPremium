package com.farsicaption.premium

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The user's own key, encrypted on device and excluded from all Android backups. */
internal class GroqKeyStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "groq-key-v1.enc"))
    companion object { private const val ALIAS = "farsicaption-groq-v1"; private val lock = Any() }
    fun hasKey(): Boolean = file.baseFile.isFile
    fun clear() = synchronized(lock) { file.delete() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    fun save(value: String) = synchronized(lock) {
        val token = value.trim()
        require(token.length in 20..512 && token.all { it.code in 33..126 }) { "کلید Groq را کامل و بدون فاصله وارد کنید" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv.size); stream.write(cipher.iv); stream.write(encrypted)
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun read(): String = synchronized(lock) {
        check(hasKey()) { "ابتدا کلید شخصی Groq را در تنظیمات آنلاین ذخیره کنید" }
        try {
            val bytes = file.readFully()
            val n = bytes[0].toInt() and 0xff
            check(n == 12 && bytes.size > n + 17)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, n + 1)))
            }
            String(cipher.doFinal(bytes.copyOfRange(n + 1, bytes.size)), Charsets.UTF_8)
        } catch (e: Exception) { throw IllegalStateException("کلید ذخیره‌شده قابل خواندن نیست؛ در تنظیمات آنلاین دوباره وارد کنید") }
    }
}
