package com.filecleaner.app.utils.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC file encryption/decryption with password-based key derivation.
 *
 * Uses PBKDF2 with 100,000 iterations for key derivation and a random
 * 16-byte IV prepended to the encrypted output file.
 *
 * Encrypted file format: [16-byte salt][16-byte IV][encrypted data]
 */
object FileEncryptor {

    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val KEY_ALGORITHM = "AES"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 16
    private const val KDF_ITERATIONS = 100_000
    private const val ENCRYPTED_EXTENSION = ".encrypted"

    data class EncryptResult(val success: Boolean, val outputPath: String, val message: String)

    /** Encrypts a file with a password. Output: originalName.encrypted */
    suspend fun encrypt(filePath: String, password: String): EncryptResult = withContext(Dispatchers.IO) {
        try {
            val src = File(filePath)
            if (!src.exists()) return@withContext EncryptResult(false, "", "File not found")

            val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(password, salt)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))

            val outputFile = File(src.absolutePath + ENCRYPTED_EXTENSION)
            outputFile.outputStream().buffered().use { out ->
                out.write(salt)
                out.write(iv)
                src.inputStream().buffered().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val encrypted = cipher.update(buffer, 0, bytesRead)
                        if (encrypted != null) out.write(encrypted)
                    }
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null) out.write(finalBlock)
                }
            }

            EncryptResult(true, outputFile.absolutePath,
                "Encrypted: ${outputFile.name} (${UndoHelper.formatBytes(outputFile.length())})")
        } catch (e: Exception) {
            EncryptResult(false, "", "Encryption failed: ${e.localizedMessage}")
        }
    }

    /** Decrypts an encrypted file. Output: original filename without .encrypted */
    suspend fun decrypt(filePath: String, password: String): EncryptResult = withContext(Dispatchers.IO) {
        try {
            val src = File(filePath)
            if (!src.exists()) return@withContext EncryptResult(false, "", "File not found")

            src.inputStream().buffered().use { input ->
                val salt = ByteArray(SALT_LENGTH)
                val iv = ByteArray(IV_LENGTH)
                if (input.read(salt) != SALT_LENGTH || input.read(iv) != IV_LENGTH) {
                    return@withContext EncryptResult(false, "", "Invalid encrypted file format")
                }

                val key = deriveKey(password, salt)
                val cipher = Cipher.getInstance(ALGORITHM)
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

                val outputName = if (src.name.endsWith(ENCRYPTED_EXTENSION))
                    src.name.removeSuffix(ENCRYPTED_EXTENSION)
                else "${src.nameWithoutExtension}_decrypted.${src.extension}"
                val outputFile = File(src.parent, outputName)

                outputFile.outputStream().buffered().use { out ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val decrypted = cipher.update(buffer, 0, bytesRead)
                        if (decrypted != null) out.write(decrypted)
                    }
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null) out.write(finalBlock)
                }

                EncryptResult(true, outputFile.absolutePath, "Decrypted: ${outputFile.name}")
            }
        } catch (e: javax.crypto.BadPaddingException) {
            EncryptResult(false, "", "Wrong password or corrupted file")
        } catch (e: Exception) {
            EncryptResult(false, "", "Decryption failed: ${e.localizedMessage}")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }
}
