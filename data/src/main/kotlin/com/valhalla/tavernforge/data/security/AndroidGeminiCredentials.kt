package com.valhalla.tavernforge.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidGeminiCredentials private constructor(private val vault: CredentialVault) : GeminiCredentials by vault {
    constructor(context: Context) : this(createVault(context.applicationContext, "production"))

    internal constructor(context: Context, testNamespace: String) : this(createVault(context, testNamespace))

    companion object {
        private val vaults = mutableMapOf<String, CredentialVault>()

        @Synchronized private fun createVault(context: Context, namespace: String): CredentialVault {
            require(namespace.matches(Regex("[a-zA-Z0-9-]{1,80}")))
            val directory = context.noBackupFilesDir.toPath().resolve("gemini-credentials-$namespace").toAbsolutePath().normalize()
            // Multiple wrappers in one app process must share lease/mutation serialization.
            return vaults.getOrPut(directory.toString()) {
                CredentialVault(AndroidCredentialCipher("com.valhalla.tavernforge.gemini.$namespace."), AtomicCredentialFiles(directory))
            }
        }
    }
}

internal class AndroidCredentialCipher(private val prefix: String) : CredentialCipher {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(id: String): String {
        require(UUID.fromString(id).toString() == id)
        return prefix + id
    }

    override fun generate(id: String) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        generator.generateKey()
    }

    override fun encrypt(id: String, clear: ByteArray, aad: ByteArray): EncryptedCredential {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, store().getKey(alias(id), null) as SecretKey)
        cipher.updateAAD(aad)
        return EncryptedCredential(cipher.iv.copyOf(), cipher.doFinal(clear))
    }

    override fun decrypt(id: String, encrypted: EncryptedCredential, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, store().getKey(alias(id), null) as SecretKey, GCMParameterSpec(128, encrypted.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted.ciphertext)
    }

    override fun delete(id: String) { store().deleteEntry(alias(id)) }

    override fun ids(): Set<String> = store().aliases().toList().filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix) }.filter { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }.toSet()
}

internal class AtomicCredentialFiles(private val directory: Path) : CredentialFiles {
    private val active get() = directory.resolve("credential.bin")

    override fun read(): ByteArray? {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
            var removed = false
            Files.newDirectoryStream(directory).use { paths ->
                paths.forEach { path ->
                    val name = path.fileName.toString()
                    if (name.startsWith("pending-") && name.endsWith(".bin") &&
                        runCatching { UUID.fromString(name.removePrefix("pending-").removeSuffix(".bin")).toString() == name.removePrefix("pending-").removeSuffix(".bin") }.getOrDefault(false) &&
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        removed = Files.deleteIfExists(path) || removed
                    }
                }
            }
            if (removed) FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
        if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) return null
        check(!Files.isSymbolicLink(directory) && Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS))
        Files.newInputStream(active, LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 8192)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    override fun replace(bytes: ByteArray) {
        check(bytes.size <= 8192)
        val existed = Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
        Files.createDirectories(directory)
        check(!Files.isSymbolicLink(directory))
        if (!existed) FileChannel.open(directory.parent, StandardOpenOption.READ).use { it.force(true) }
        val temporary = directory.resolve("pending-${UUID.randomUUID()}.bin")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temporary) }
    }
}
