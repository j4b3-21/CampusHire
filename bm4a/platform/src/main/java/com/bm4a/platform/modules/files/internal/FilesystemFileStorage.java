package com.bm4a.platform.modules.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.bm4a.platform.modules.shared.error.ApiException;

/**
 * Stores objects as files below a root directory, for tests and single-node installs without an object store. Writes
 * go to a temporary sibling first and are moved into place atomically, so readers never see partial content.
 */
class FilesystemFileStorage implements FileStorage {

    private final Path root;

    FilesystemFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot create file storage directory " + this.root, ex);
        }
    }

    @Override
    public void put(String key, Path source, long sizeBytes, String contentType) {
        Path target = resolve(key);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            deleteQuietly(partial);
            throw unavailable(ex);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (NoSuchFileException ex) {
            throw ApiException.notFound("File");
        } catch (IOException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw unavailable(ex);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Storage key escapes the storage root");
        }
        return path;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the next put of the same key replaces it
        }
    }

    private static ApiException unavailable(IOException ex) {
        ApiException failure = ApiException.unavailable("file_storage_unavailable", "File storage is unavailable");
        failure.initCause(ex);
        return failure;
    }
}
