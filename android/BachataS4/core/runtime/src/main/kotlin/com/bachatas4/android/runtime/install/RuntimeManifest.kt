package com.bachatas4.android.runtime.install

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeManifest(
    val schemaVersion: Int,
    val runtimeVersion: String,
    val protocolVersion: Int,
    val files: List<RuntimeFile>,
)

@Serializable
data class RuntimeFile(
    val path: String,
    val size: Long,
    val sha256: String,
)

fun RuntimeManifest.contentFingerprint(): String {
    // Deterministic over the manifest's declared file list (path + size + sha256),
    // sorted so JSON key/array ordering can't change the result. Used by
    // RuntimeInstaller to detect a bundled runtime.zip whose *contents* changed
    // even though runtimeVersion (a fixed upstream box64 revision string) did
    // not - without this, an already-installed device would keep an old
    // extraction forever, since runtimeVersion alone never changes between our
    // own fixes to runtime.zip's contents.
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.path }.forEach { file ->
        digest.update(file.path.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(file.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(file.sha256.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().toHexString()
}

fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().toHexString()
}

internal fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
