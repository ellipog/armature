package dev.ellipog.armature.api.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writes a file so that it is either its old content or its new content, and never a prefix of either.
 *
 * <p>The write half of {@link JsonDocument}, and it exists because the obvious version of the job is
 * the one that loses data. {@code Files.writeString(file, text)} opens the real path with
 * {@code CREATE} and {@code TRUNCATE_EXISTING}: the old content is gone the instant the file is
 * opened, and everything after that is an unprotected window. A crash, an out-of-memory kill or a
 * power cut inside that window leaves a truncated or empty file — and for a content file that is
 * somebody's work gone, with nothing on disk to recover it from.
 *
 * <h2>What this does instead</h2>
 *
 * <p>Write the whole text to a temporary file beside the target, force it to storage, then
 * <b>rename</b> it over the target. A rename within one directory is a single filesystem operation, so
 * there is no moment at which the target holds part of the new text. Every failure path leaves the
 * original exactly as it was.
 *
 * <h2>Why the temporary name begins with an underscore</h2>
 *
 * <p>Because the one case this cannot clean up after is the process dying between the write and the
 * rename, and what it would leave behind is a stray file in a directory full of content.
 * {@code DeclaredPaths.isIgnoredName} treats any name beginning with {@code _} as "not content, skip"
 * at <b>every</b> level, which is the same rule that keeps {@code _schema/} out of the loader's walk —
 * so a leftover temporary file is invisible to every walker, listing and consistency check in either
 * mod. The {@code .tmp} suffix is a second, weaker answer (a document a reader will accept must end in
 * {@code .json}), and it is kept because two independent answers to "is this content" is one more
 * than one.
 *
 * <h2>What this does not promise</h2>
 *
 * <p>Durability of the <i>rename</i> against a power cut. {@code SYNC} forces the temporary file's
 * bytes to the storage device before the rename, so the content is there to be pointed at; but Java
 * cannot portably force a directory entry to storage, so a power loss in the instant after the rename
 * can in principle lose the rename itself. That failure leaves the <b>old</b> file intact, which is the
 * direction that costs an edit rather than a file — and it is named here rather than left implied,
 * because a comment claiming more than the code does is how a guarantee stops being one.
 */
public final class JsonWrite {

    private JsonWrite() {
    }

    /**
     * Writes {@code text} to {@code file} atomically, creating the parent directory if it is absent.
     *
     * @param file the file to replace. It need not exist; it must name a file rather than a directory.
     * @param text the complete new content. It is written in UTF-8, byte for byte — no newline is
     *             added, because a caller that wants a trailing newline is a caller that has already
     *             put one in the string.
     * @throws IOException if the temporary file cannot be written, or the rename cannot be done. The
     *                     target is untouched in either case, and the temporary file is removed.
     */
    public static void atomically(Path file, String text) throws IOException {
        // Through toAbsolutePath, not getParent(): a bare file name has no parent, and the caller that
        // passes one should get its file written rather than a NullPointerException.
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);

        Path temporary = parent.resolve("_" + file.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    // WRITE is not implied here. When options are given they are used as they are --
                    // the "CREATE, TRUNCATE_EXISTING and WRITE" default applies only when none are --
                    // and SYNC is a durability modifier rather than an access mode, so a list without
                    // WRITE opens nothing and fails at runtime rather than at compile time.
                    StandardOpenOption.WRITE,
                    // The bytes are on the device before the rename points at them. Without this the
                    // rename could be durable while the content it names is not.
                    StandardOpenOption.SYNC);

            move(temporary, file);
        }
        catch (IOException | RuntimeException e) {
            // Best effort, and deliberately swallowing its own failure: the exception that matters is
            // the one being rethrown, and replacing it with "could not delete the temporary file"
            // would hide the fault the caller has to act on.
            try {
                Files.deleteIfExists(temporary);
            }
            catch (IOException | RuntimeException ignored) {
                // Nothing useful to do, and nothing useful to say. See above.
            }
            throw e;
        }
    }

    /**
     * The rename, with a fallback for a filesystem that cannot do it atomically.
     *
     * <p>{@code ATOMIC_MOVE} is what the whole class is for, but it is not offered everywhere — some
     * network shares and a few virtual filesystems refuse it — and an unhandled
     * {@link AtomicMoveNotSupportedException} here would turn "this share cannot rename atomically"
     * into a crash on a save. The fallback is still a write-then-rename, so it never truncates the
     * target in place; it is only the atomicity of the rename that is given up, and on a filesystem
     * that cannot offer it there was none to give up.
     */
    private static void move(Path temporary, Path file) throws IOException {
        try {
            Files.move(temporary, file,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
