/*
 * Copyright (c) KleinerHacker alias Pfeiffer C Soft 2026.
 * This work is licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */

package org.pcsoft.framework.pluggiat.persistence

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Creates files that only their owner can read or write, and replaces their content atomically.
 *
 * Shared by every part of the framework that writes security-relevant state to disk (the integrity
 * key, the persisted plugin state): both properties matter for the same reason, which is that the file
 * decides something another local process must not be able to influence.
 *
 * * *Owner-only*, applied at creation rather than afterwards: a file created with the platform default
 *   and narrowed a moment later is readable by others in between, which for a key file is all an
 *   attacker needs.
 * * *Atomic replacement*, via a temporary file in the same directory plus a rename: a reader never sees
 *   a half-written file, and a crash mid-write leaves the previous state intact rather than a truncated
 *   one. Writing in place would make "checked and accepted" state corruptible by nothing more than bad
 *   timing.
 */
internal object OwnerOnlyFiles {

    /** POSIX `600`. */
    val OWNER_ONLY_PERMISSIONS: Set<PosixFilePermission> =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    /**
     * Creates [path] as a new, empty, owner-only file.
     *
     * @return `false` if the platform supports neither POSIX permissions nor ACLs, in which case the
     * file exists but carries this platform's default access rights
     * @throws java.nio.file.FileAlreadyExistsException if [path] already exists - the caller decides
     * what that means, it is never silently overwritten
     */
    fun createEmpty(path: Path): Boolean {
        if (isPosix()) {
            // SECURITY: the permissions are part of the creation call, so the file is never briefly readable by
            // SECURITY: others between being created and being narrowed.
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS))
            return true
        }
        Files.createFile(path)
        return restrictToOwnerAcl(path)
    }

    /**
     * Writes [bytes] to [path] by filling a temporary file next to it and renaming that over [path].
     *
     * The temporary file is created owner-only and lives in the *same* directory, because a rename is
     * only atomic within one file system. Where the platform cannot rename atomically at all, the move
     * falls back to a plain replacing move: still better than writing into [path] directly, since the
     * content is complete before it becomes visible under that name.
     */
    fun writeAtomically(path: Path, bytes: ByteArray) {
        val directory = path.toAbsolutePath().parent ?: error("Cannot write '$path': it has no parent directory")
        Files.createDirectories(directory)
        // SECURITY: the temporary file lives in the *same* directory, because a rename is only atomic within
        // SECURITY: one file system.
        val temporary = Files.createTempFile(directory, path.fileName.toString(), TEMPORARY_SUFFIX)
        try {
            // SECURITY: narrowed before anything is written into it.
            restrictExisting(temporary)
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // SECURITY: only ever still present if the move failed - leaving it behind would leak the
            // SECURITY: state's content under a predictable temporary name.
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    /**
     * Narrows an existing file to owner-only access. Used for a file that had to be created first (a
     * temporary file) - never as a substitute for [createEmpty].
     */
    fun restrictExisting(path: Path): Boolean {
        if (isPosix()) {
            Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS)
            return true
        }
        return restrictToOwnerAcl(path)
    }

    /**
     * The permissions of [path] that grant access beyond its owner, empty if there are none or if the
     * platform does not report POSIX permissions. Used to *report* an over-permissive pre-existing file
     * rather than to silently change what a host set up deliberately.
     */
    fun accessBeyondOwner(path: Path): Set<PosixFilePermission> {
        if (!isPosix()) return emptySet()
        val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return emptySet()
        return permissions - OWNER_ONLY_PERMISSIONS
    }

    private fun isPosix(): Boolean = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    /**
     * Replaces [path]'s ACL with a single full-access entry for its owner - the Windows equivalent of
     * `600`.
     */
    private fun restrictToOwnerAcl(path: Path): Boolean {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return false
        val ownerOnly = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(view.owner)
            .setPermissions(AclEntryPermission.entries.toSet())
            .build()
        view.acl = listOf(ownerOnly)
        return true
    }

    private const val TEMPORARY_SUFFIX = ".tmp"
}
