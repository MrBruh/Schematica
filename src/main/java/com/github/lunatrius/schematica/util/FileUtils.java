package com.github.lunatrius.schematica.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import com.github.lunatrius.schematica.reference.Reference;

public class FileUtils {

    // http://stackoverflow.com/a/3758880/1166946
    public static String humanReadableByteCount(final long bytes) {
        final int unit = 1024;
        if (bytes < unit) {
            return bytes + " B";
        }

        int exp = (int) (Math.log(bytes) / Math.log(unit));
        final String pre = "KMGTPE".charAt(exp - 1) + "i";

        return String.format("%3.0f %sB", bytes / Math.pow(unit, exp), pre);
    }

    public static boolean contains(final File root, final String filename) {
        return contains(root, new File(root, filename));
    }

    // http://stackoverflow.com/q/18227634/1166946
    public static boolean contains(final File root, final File file) {
        try {
            return file.getCanonicalPath()
                .startsWith(root.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            Reference.logger.error("", e);
        }

        return false;
    }

    /**
     * The path of {@code file} below {@code root}, with forward slashes whatever the platform, or null when the file
     * is not below the root.
     */
    public static String relativePath(final File root, final File file) {
        try {
            final Path rootPath = root.getCanonicalFile()
                .toPath();
            final Path filePath = file.getCanonicalFile()
                .toPath();
            if (filePath.startsWith(rootPath) && !filePath.equals(rootPath)) {
                return rootPath.relativize(filePath)
                    .toString()
                    .replace(File.separatorChar, '/');
            }
        } catch (IOException e) {
            Reference.logger.error("", e);
        }

        return null;
    }
}
