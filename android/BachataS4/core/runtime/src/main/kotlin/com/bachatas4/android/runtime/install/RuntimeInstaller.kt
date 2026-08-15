package com.bachatas4.android.runtime.install

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.zip.ZipInputStream

class RuntimeInstaller(
    private val runtimeRoot: Path,
    private val promote: (staging: Path, target: Path) -> Unit = { staging, target ->
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    },
    // Observability hook so callers can log which path install() actually took, without
    // RuntimeInstaller itself depending on any Android logging API (keeps this class pure
    // JVM-testable). Default no-op preserves behavior for existing callers/tests.
    private val onDecision: (String) -> Unit = {},
) {
    fun install(bundle: InputStream, manifest: RuntimeManifest): Result<Path> = try {
        Result.success(installOrThrow(bundle, manifest))
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    private fun installOrThrow(bundle: InputStream, manifest: RuntimeManifest): Path {
        val version = validateVersion(manifest.runtimeVersion)
        val root = runtimeRoot.toAbsolutePath().normalize()
        val staging = root.resolve(".staging-$version").normalize()
        val target = root.resolve(version).normalize()
        requireContained(root, staging)
        requireContained(root, target)

        val fingerprint = manifest.contentFingerprint()
        if (Files.isDirectory(target)) {
            val marker = target.resolve(FINGERPRINT_FILE)
            val markerFingerprint = runCatching {
                String(Files.readAllBytes(marker), Charsets.UTF_8).trim()
            }.getOrNull()
            val markerMatches = markerFingerprint == fingerprint
            val contentVerified = markerMatches && verifyInstalledContent(target, manifest.files)
            if (contentVerified) {
                // Same runtimeVersion, marker claims identical content, AND the actual
                // on-disk files verify against the manifest's declared hashes: trust it.
                onDecision(
                    "up-to-date: fingerprint=${fingerprint.take(12)} verified against on-disk content, skipping re-extraction"
                )
                return target
            }
            // Either the marker doesn't match (bundled runtime.zip's content changed
            // since this device last installed/updated -- runtimeVersion alone doesn't
            // reflect that), or it matched but the files on disk don't actually verify
            // (corruption, external tampering, or some other process silently
            // reintroducing stale content the marker can't see). Either way the
            // existing extraction can't be trusted; wipe and re-extract fresh.
            val reason = if (!markerMatches) {
                "fingerprint mismatch (marker=${markerFingerprint?.take(12) ?: "none"}, expected=${fingerprint.take(12)})"
            } else {
                "on-disk content failed verification despite matching fingerprint marker"
            }
            onDecision("re-extracting stale install at $target: $reason")
            deleteRecursively(target)
        } else {
            onDecision("fresh install: no prior directory at $target")
        }

        Files.createDirectories(root)
        deleteRecursively(staging)
        Files.createDirectory(staging)

        try {
            val declaredFiles = declarationsByPath(staging, manifest.files)
            extractAndVerify(bundle, staging, declaredFiles)
            Files.write(staging.resolve(FINGERPRINT_FILE), fingerprint.toByteArray(Charsets.UTF_8))
            if (Files.exists(target)) throw FileAlreadyExistsException(target.toString())
            promote(staging, target)
            onDecision(
                "extraction complete: ${declaredFiles.size} files, fingerprint=${fingerprint.take(12)} -> $target"
            )
            return target
        } finally {
            deleteRecursively(staging)
        }
    }

    /**
     * Re-verifies actual on-disk file content (size + SHA-256) against what the manifest
     * declares. Deliberately independent of the fingerprint marker file: the marker only
     * proves *a* successful extraction happened at some point, not that the files are
     * still what that extraction wrote (e.g. an OEM app-data restore mechanism silently
     * reintroducing an older extraction's files, alongside a marker from a *different*
     * install that happens to still be present).
     */
    private fun verifyInstalledContent(target: Path, files: List<RuntimeFile>): Boolean = files.all { file ->
        runCatching {
            val path = safeRelativePath(target, file.path)
            val resolved = target.resolve(path)
            if (!Files.isRegularFile(resolved)) return@runCatching false
            if (Files.size(resolved) != file.size) return@runCatching false
            Files.newInputStream(resolved).use { sha256(it) } == file.sha256
        }.getOrDefault(false)
    }

    private fun declarationsByPath(
        staging: Path,
        files: List<RuntimeFile>,
    ): Map<Path, RuntimeFile> = buildMap {
        files.forEach { file ->
            require(file.size >= 0) { "Negative runtime file size: ${file.path}" }
            require(HASH_PATTERN.matches(file.sha256)) { "Invalid SHA-256: ${file.path}" }
            val path = safeRelativePath(staging, file.path)
            require(put(path, file) == null) { "Duplicate runtime path: ${file.path}" }
        }
    }

    private fun extractAndVerify(
        bundle: InputStream,
        staging: Path,
        declaredFiles: Map<Path, RuntimeFile>,
    ) {
        val extracted = mutableSetOf<Path>()
        ZipInputStream(BufferedInputStream(bundle)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Directory entries are not allowed: ${entry.name}" }
                val relativePath = safeRelativePath(staging, entry.name)
                require(extracted.add(relativePath)) { "Duplicate ZIP path: ${entry.name}" }
                val declaration = requireNotNull(declaredFiles[relativePath]) {
                    "Unexpected ZIP entry: ${entry.name}"
                }
                extractFile(zip, staging.resolve(relativePath), declaration)
                zip.closeEntry()
            }
        }

        val missing = declaredFiles.keys - extracted
        require(missing.isEmpty()) { "Missing ZIP entries: ${missing.joinToString()}" }
    }

    private fun extractFile(
        input: InputStream,
        target: Path,
        declaration: RuntimeFile,
    ) {
        Files.createDirectories(target.parent)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= declaration.size) { "Size mismatch: ${declaration.path}" }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        target.toFile().setExecutable(false, false)
        check(!Files.isExecutable(target)) { "Runtime file is executable: ${declaration.path}" }
        require(size == declaration.size) { "Size mismatch: ${declaration.path}" }
        require(digest.digest().toHexString() == declaration.sha256) {
            "SHA-256 mismatch: ${declaration.path}"
        }
    }

    private fun safeRelativePath(staging: Path, rawPath: String): Path {
        require(rawPath.isNotBlank()) { "Runtime path is blank" }
        val relative = Paths.get(rawPath.replace('\\', '/')).normalize()
        if (relative.isAbsolute || relative.toString().isEmpty()) {
            throw SecurityException("Absolute or empty runtime path: $rawPath")
        }
        requireContained(staging, staging.resolve(relative).normalize())
        return relative
    }

    private fun requireContained(parent: Path, candidate: Path) {
        if (!candidate.startsWith(parent)) {
            throw SecurityException("Runtime path escapes install root: $candidate")
        }
    }

    private fun validateVersion(version: String): String {
        require(VERSION_PATTERN.matches(version)) { "Unsafe runtime version: $version" }
        require(version != "." && version != "..") { "Unsafe runtime version: $version" }
        return version
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
        val VERSION_PATTERN = Regex("[A-Za-z0-9._-]+")
        const val FINGERPRINT_FILE = ".runtime-fingerprint"
    }
}
