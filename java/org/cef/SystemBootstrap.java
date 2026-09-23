// Copyright (c) 2020 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
package org.cef;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * To allow customization of System.load() calls by supplying a different
 * implementation.  You'll want to call <code>setLoader</code> with your custom
 * implementation before calling into any other CEF classes which then in turn
 * will start triggering libraries to be loaded at runtime.
 */
public class SystemBootstrap {
    static public interface RuntimeDownloadProvider {
        public URL getRuntimeUrl(String version, String platform) throws IOException;
    }

    static public interface DownloadProgressListener {
        public void onProgress(
                String platform, URL url, long downloadedBytes, long totalBytes, double percent);
    }

    /**
     * Simple interface for how a library by name should be loaded.
     */
    static public interface Loader {
        public void loadLibrary(String libname);
    }

    /**
     * Default implementation is to call System.loadLibrary
     */
    static private Loader loader_ = new Loader() {
        @Override
        public void loadLibrary(String libname) {
            if (EmbeddedNativeLoader.loadLibrary(libname)) return;
            System.loadLibrary(libname);
        }
    };

    static public void setLoader(Loader loader) {
        if (loader == null) {
            throw new NullPointerException("Loader cannot be null");
        }
        loader_ = loader;
    }

    static public void loadLibrary(String libname) {
        loader_.loadLibrary(libname);
    }

    static public String getBundledLibraryPath() {
        return EmbeddedNativeLoader.getLibraryPath();
    }

    static public void setRuntimeDownloadProvider(RuntimeDownloadProvider provider) {
        EmbeddedNativeLoader.setRuntimeDownloadProvider(provider);
    }

    static public void setDownloadProgressListener(DownloadProgressListener listener) {
        EmbeddedNativeLoader.setDownloadProgressListener(listener);
    }

    private static final class EmbeddedNativeLoader {
        private static final String RESOURCE_ROOT = "org/cef/native";
        private static final String DEFAULT_REPOSITORY = "DanielTM999/java-cef";
        private static final String DEFAULT_CACHE_VERSION = "1.0.0";
        private static final String VERSION_RESOURCE = "/org/cef/jcef-orion-version.properties";
        private static boolean versionResolved_;
        private static String version_;
        private static final Set<String> loaded_ = new HashSet<String>();
        private static Path libraryPath_;
        private static boolean runtimeResolveAttempted_ = false;
        private static final String RUNTIME_MARKER = ".jcef-runtime-complete";
        private static final String RUNTIME_LOCK = ".jcef-runtime-lock";
        private static FileChannel runtimeLockChannel_;
        private static FileLock runtimeLock_;
        private static RuntimeDownloadProvider downloadProvider_ =
                new DefaultRuntimeDownloadProvider();
        private static DownloadProgressListener progressListener_;

        static synchronized boolean loadLibrary(String libname) {
            String filename = mapLibraryName(libname);
            if (filename == null) return false;

            Path root = ensureRuntimeAvailable(filename);
            if (root == null) return false;

            Path library = root.resolve(filename);
            if (!Files.isRegularFile(library)) return false;

            String key = library.toAbsolutePath().normalize().toString();
            if (loaded_.contains(key)) return true;

            try {
                System.load(key);
            } catch (UnsatisfiedLinkError e) {
                // Orion fork addition. See MODIFICATIONS.md.
                // A runtime that cannot be loaded is unusable; drop the
                // completion marker so the next run downloads it again instead
                // of failing forever on a damaged cache.
                invalidateRuntimeCache(root);
                throw e;
            }
            loaded_.add(key);
            return true;
        }

        static synchronized String getLibraryPath() {
            Path root = ensureRuntimeAvailable(null);
            return root == null ? null : root.toAbsolutePath().normalize().toString();
        }

        static synchronized void setRuntimeDownloadProvider(RuntimeDownloadProvider provider) {
            downloadProvider_ = provider;
            if (libraryPath_ == null) runtimeResolveAttempted_ = false;
        }

        static synchronized void setDownloadProgressListener(DownloadProgressListener listener) {
            progressListener_ = listener;
        }

        private static Path ensureRuntimeAvailable(String requestedLibrary) {
            if (libraryPath_ != null) return libraryPath_;
            if (runtimeResolveAttempted_) return null;
            runtimeResolveAttempted_ = true;

            String platform = platformName();
            if (platform == null) return null;

            Path embedded = extractBundledRuntime(platform);
            if (embedded != null) return embedded;

            if (requestedLibrary != null && isOnJavaLibraryPath(requestedLibrary)) return null;

            Path downloaded = downloadRuntime(platform);
            if (downloaded != null) return downloaded;
            return null;
        }

        private static Path extractBundledRuntime(String platform) {
            String manifestResource = RESOURCE_ROOT + "/" + platform + "/MANIFEST";
            URL manifestUrl = getResource(manifestResource);
            if (manifestUrl == null) return null;

            Path root = runtimeRoot(platform);
            try (InputStream in = openResource(manifestResource)) {
                if (in == null) return null;

                List<String> entries = readManifest(in);
                Files.createDirectories(root);
                for (String entry : entries) {
                    if (entry.length() == 0 || entry.startsWith("#")) continue;
                    extractEntry(platform, root, entry);
                }
                writeMarker(root, "source=embedded");
            } catch (IOException e) {
                UnsatisfiedLinkError error = new UnsatisfiedLinkError(
                        "Failed to extract bundled JCEF native runtime: " + e.getMessage());
                error.initCause(e);
                throw error;
            }

            libraryPath_ = macLibraryPath(root);
            cleanupStaleRuntimesAsync(platform);
            return libraryPath_;
        }

        private static Path downloadRuntime(String platform) {
            if (!isDownloadEnabled() || downloadProvider_ == null) return null;

            String version = cacheVersion();
            URL url;
            try {
                url = downloadProvider_.getRuntimeUrl(version, platform);
            } catch (IOException e) {
                throw runtimeLoadError("Failed to resolve JCEF native runtime download URL", e);
            }
            if (url == null) return null;

            Path root = runtimeRoot(platform);
            Path cache = root.getParent();
            if (isCurrentRuntime(root) && containsRuntimeLibrary(root)) {
                libraryPath_ = macLibraryPath(root);
                cleanupStaleRuntimesAsync(platform);
                return libraryPath_;
            }

            Path zipPath = cache.resolve("jcef-runtime-" + platform + "-" + version + ".zip");
            try {
                Files.createDirectories(cache);
                download(url, zipPath, platform);
                extractZip(zipPath, root);
                writeMarker(root, "url=" + url);
                libraryPath_ = macLibraryPath(root);
                cleanupStaleRuntimesAsync(platform);
                return libraryPath_;
            } catch (IOException e) {
                throw runtimeLoadError(
                        "Failed to download JCEF native runtime from " + url, e);
            } finally {
                try {
                    Files.deleteIfExists(zipPath);
                } catch (IOException ignored) {
                }
            }
        }

        private static void download(URL url, Path target, String platform) throws IOException {
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(
                    Integer.getInteger("jcef.orion.runtime.connect-timeout-ms", 15000));
            connection.setReadTimeout(
                    Integer.getInteger("jcef.orion.runtime.read-timeout-ms", 30000));
            long total = connection.getContentLengthLong();
            long read = 0L;
            byte[] buffer = new byte[8192];
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                    OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    read += len;
                    reportProgress(platform, url, read, total);
                }
            }
            if (total == 0) reportProgress(platform, url, read, total);

            // Orion fork addition. See MODIFICATIONS.md.
            // A server that closes the connection early ends the read loop
            // without an exception, which used to leave a truncated zip behind.
            if (total > 0 && read != total) {
                throw new IOException("Incomplete download from " + url + ": got " + read
                        + " of " + total + " bytes");
            }
        }

        private static void invalidateRuntimeCache(Path root) {
            try {
                Files.deleteIfExists(root.resolve(RUNTIME_MARKER));
            } catch (IOException ignored) {
            }
        }

        private static void extractZip(Path zipPath, Path root) throws IOException {
            Files.createDirectories(root);
            // Orion fork addition. See MODIFICATIONS.md.
            // ZipFile reads the central directory, so a truncated archive fails
            // here instead of silently producing short files, and every entry
            // size is known up front and verified after extraction.
            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    Path target = root.resolve(entry.getName()).normalize();
                    if (!target.startsWith(root)) {
                        throw new IOException("Invalid runtime zip entry: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    long expected = entry.getSize();
                    long actual = Files.size(target);
                    if (expected >= 0 && expected != actual) {
                        throw new IOException("Truncated runtime entry " + entry.getName()
                                + ": got " + actual + " of " + expected + " bytes");
                    }
                    applyPermissions(target);
                }
            }
            if (!containsRuntimeLibrary(root)) {
                throw new IOException("Downloaded runtime zip does not contain a JCEF library");
            }
        }

        private static void reportProgress(String platform, URL url, long read, long total) {
            DownloadProgressListener listener = progressListener_;
            if (listener == null) return;
            double percent = total > 0 ? Math.min(100.0d, (read * 100.0d) / total) : -1.0d;
            listener.onProgress(platform, url, read, total, percent);
        }

        private static boolean isDownloadEnabled() {
            String value = System.getProperty("jcef.orion.runtime.download", "true");
            return !"false".equalsIgnoreCase(value) && !"0".equals(value);
        }

        private static UnsatisfiedLinkError runtimeLoadError(String message, Throwable cause) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError(message + ": "
                    + cause.getMessage());
            error.initCause(cause);
            return error;
        }

        private static boolean containsRuntimeLibrary(Path root) {
            if (root == null) return false;
            String filename = mapLibraryName("jcef");
            if (filename == null) return false;
            return Files.isRegularFile(macLibraryPath(root).resolve(filename));
        }

        private static boolean isOnJavaLibraryPath(String filename) {
            String libraryPath = System.getProperty("java.library.path");
            if (libraryPath == null || libraryPath.length() == 0) return false;

            String[] paths = libraryPath.split(System.getProperty("path.separator"));
            for (String path : paths) {
                if (Files.isRegularFile(Paths.get(path).resolve(filename))) return true;
            }
            return false;
        }

        private static void extractEntry(String platform, Path root, String entry)
                throws IOException {
            String resource = RESOURCE_ROOT + "/" + platform + "/" + entry;
            Path target = root.resolve(entry).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("Invalid bundled native entry: " + entry);
            }

            URL resourceUrl = getResource(resource);
            if (resourceUrl == null) {
                throw new IOException("Bundled native resource not found: " + resource);
            }

            URLConnection connection = resourceUrl.openConnection();
            long expectedSize = connection.getContentLengthLong();
            if (expectedSize >= 0 && Files.isRegularFile(target)
                    && Files.size(target) == expectedSize) {
                applyPermissions(target);
                return;
            }

            try (InputStream resourceIn = connection.getInputStream()) {
                Files.createDirectories(target.getParent());
                Files.copy(resourceIn, target, StandardCopyOption.REPLACE_EXISTING);
            }
            applyPermissions(target);
        }

        private static void applyPermissions(Path target) {
            File file = target.toFile();
            file.setReadable(true, true);
            file.setWritable(true, true);
            file.setExecutable(true, true);
        }

        private static List<String> readManifest(InputStream in) throws IOException {
            return java.util.Arrays.asList(new String(readAllBytes(in), "UTF-8").split("\\R"));
        }

        private static byte[] readAllBytes(InputStream in) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            return out.toByteArray();
        }

        private static URL getResource(String resource) {
            ClassLoader loader = SystemBootstrap.class.getClassLoader();
            if (loader != null) return loader.getResource(resource);
            return ClassLoader.getSystemResource(resource);
        }

        private static InputStream openResource(String resource) {
            ClassLoader loader = SystemBootstrap.class.getClassLoader();
            if (loader != null) return loader.getResourceAsStream(resource);
            return ClassLoader.getSystemResourceAsStream(resource);
        }

        // Orion fork addition. See MODIFICATIONS.md.
        // Every runtime lives under <base>/<version>/<platform>, also when the
        // embedder overrides the base with jcef.orion.cache.path, so a new
        // release never reuses the native runtime of an older one.
        private static Path cacheBase() {
            String override = System.getProperty("jcef.orion.cache.path");
            if (override != null && override.length() > 0) return Paths.get(override);

            String home = System.getProperty("user.home");
            if (home == null || home.length() == 0) {
                return Paths.get(System.getProperty("java.io.tmpdir"), "jcef-orion");
            }
            return Paths.get(home, ".jcef-orion");
        }

        private static Path runtimeRoot(String platform) {
            return cacheBase().resolve(cacheVersion()).resolve(platform);
        }

        private static void writeMarker(Path root, String detail) throws IOException {
            String content = "version=" + cacheVersion() + "\n" + detail + "\n";
            Files.write(root.resolve(RUNTIME_MARKER), content.getBytes("UTF-8"));
        }

        private static boolean isCurrentRuntime(Path root) {
            Path marker = root.resolve(RUNTIME_MARKER);
            if (!Files.isRegularFile(marker)) return false;
            try {
                for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                    if (line.equals("version=" + cacheVersion())) return true;
                }
            } catch (IOException ignored) {
            }
            return false;
        }

        // Removes runtimes of other versions (and the pre-versioned layout
        // <base>/<platform>) in the background. Only directories carrying the
        // runtime marker are touched, and each is renamed before deletion: on
        // Windows the rename fails while another process still has its
        // libraries loaded, so a runtime in use is left alone.
        private static void cleanupStaleRuntimesAsync(final String platform) {
            lockRuntimeInUse(runtimeRoot(platform));
            if ("false".equalsIgnoreCase(System.getProperty("jcef.orion.runtime.cleanup"))) return;
            if (resolvedVersion() == null) return;
            final Path base = cacheBase();
            final String current = cacheVersion();
            Thread cleaner = new Thread(new Runnable() {
                @Override
                public void run() {
                    cleanupStaleRuntimes(base, platform, current);
                }
            }, "Orion-JCEF-Runtime-Cleanup");
            cleaner.setDaemon(true);
            cleaner.start();
        }

        static void cleanupStaleRuntimes(Path base, String platform, String current) {
            removeIfStaleRuntime(base.resolve(platform));
            File[] versions = base.toFile().listFiles();
            if (versions == null) return;
            for (File version : versions) {
                if (!version.isDirectory() || version.getName().equals(current)) continue;
                Path candidate = version.toPath().resolve(platform);
                if (removeIfStaleRuntime(candidate)) {
                    String[] left = version.list();
                    if (left != null && left.length == 0) version.delete();
                }
            }
        }

        private static boolean removeIfStaleRuntime(Path dir) {
            if (Files.isSymbolicLink(dir) || !Files.isRegularFile(dir.resolve(RUNTIME_MARKER))) {
                return false;
            }
            Path lockFile = dir.resolve(RUNTIME_LOCK);
            if (Files.isRegularFile(lockFile)) {
                if (!isUnused(lockFile)) return false;
            } else if (!OS.isWindows()) {
                // Runtimes from before the lock existed cannot be proven unused
                // on POSIX, where renaming a directory in use succeeds.
                return false;
            }
            Path doomed = dir.resolveSibling(dir.getFileName() + ".stale-" + System.nanoTime());
            try {
                Files.move(dir, doomed);
            } catch (IOException inUse) {
                return false;
            }
            deleteRecursively(doomed);
            return true;
        }

        private static boolean isUnused(Path lockFile) {
            try (FileChannel channel = FileChannel.open(
                         lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, false);
                if (lock == null) return false;
                lock.release();
                return true;
            } catch (IOException | OverlappingFileLockException e) {
                return false;
            }
        }

        // Held for the lifetime of the process so that other processes can
        // tell this runtime is in use.
        private static void lockRuntimeInUse(Path root) {
            if (runtimeLock_ != null) return;
            try {
                FileChannel channel = FileChannel.open(root.resolve(RUNTIME_LOCK),
                        StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, true);
                if (lock == null) {
                    channel.close();
                    return;
                }
                runtimeLockChannel_ = channel;
                runtimeLock_ = lock;
            } catch (IOException | OverlappingFileLockException | UnsupportedOperationException ignored) {
            }
        }

        private static void deleteRecursively(Path root) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        try {
                            Files.delete(file);
                        } catch (IOException ignored) {
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException error) {
                        try {
                            Files.delete(dir);
                        } catch (IOException ignored) {
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {
            }
        }

        private static String cacheVersion() {
            String version = resolvedVersion();
            return version == null ? DEFAULT_CACHE_VERSION : version;
        }

        // Orion fork addition. See MODIFICATIONS.md.
        // Fat/shaded jars (e.g. maven-shade) replace the jar manifest, which drops
        // Implementation-Version. The version is therefore also packaged as a
        // resource inside org/cef, which survives repackaging.
        private static synchronized String resolvedVersion() {
            if (versionResolved_) return version_;
            versionResolved_ = true;
            version_ = nonEmpty(System.getProperty("jcef.orion.version"));
            if (version_ == null) version_ = versionFromResource();
            if (version_ == null) {
                Package pkg = SystemBootstrap.class.getPackage();
                version_ = nonEmpty(pkg == null ? null : pkg.getImplementationVersion());
            }
            if (version_ == null) {
                System.err.println("[JCEF] jcef-orion version unknown (no " + VERSION_RESOURCE
                        + " and no Implementation-Version); using " + DEFAULT_CACHE_VERSION
                        + " and skipping cleanup of other runtimes. Set -Djcef.orion.version to fix.");
            }
            return version_;
        }

        private static String versionFromResource() {
            try (InputStream in = SystemBootstrap.class.getResourceAsStream(VERSION_RESOURCE)) {
                if (in == null) return null;
                java.util.Properties properties = new java.util.Properties();
                properties.load(in);
                return nonEmpty(properties.getProperty("version"));
            } catch (IOException e) {
                return null;
            }
        }

        private static String nonEmpty(String value) {
            if (value == null) return null;
            value = value.trim();
            return value.length() == 0 ? null : value;
        }

        private static Path macLibraryPath(Path root) {
            if (!OS.isMacintosh()) return root;
            return root.resolve("jcef_app.app").resolve("Contents").resolve("Java");
        }

        private static String platformName() {
            if (OS.isWindows()) return "win64";
            if (OS.isLinux()) return "linux64";
            if (OS.isMacintosh()) return "macosx64";
            return null;
        }

        private static String mapLibraryName(String libname) {
            if (OS.isWindows()) {
                if ("jawt".equals(libname)) return null;
                if ("chrome_elf".equals(libname)) return "chrome_elf.dll";
                if ("libcef".equals(libname)) return "libcef.dll";
                if ("jcef".equals(libname)) return "jcef.dll";
            } else if (OS.isLinux()) {
                if ("cef".equals(libname)) return "libcef.so";
                if ("jcef".equals(libname)) return "libjcef.so";
            } else if (OS.isMacintosh()) {
                if ("jcef".equals(libname)) return "libjcef.dylib";
            }
            return null;
        }

        private static final class DefaultRuntimeDownloadProvider
                implements RuntimeDownloadProvider {
            @Override
            public URL getRuntimeUrl(String version, String platform) throws IOException {
                String tag = System.getProperty("jcef.orion.release.tag");
                if (tag == null || tag.length() == 0) tag = "v" + version;

                String template = System.getProperty("jcef.orion.runtime.url");
                if (template == null || template.length() == 0) {
                    String base = System.getProperty("jcef.orion.runtime.base-url");
                    if (base == null || base.length() == 0) {
                        base = "https://github.com/" + DEFAULT_REPOSITORY
                                + "/releases/download/{tag}/";
                    }
                    if (!base.endsWith("/")) base = base + "/";
                    template = base + "jcef-runtime-{platform}-{version}.zip";
                }

                String value = template.replace("{version}", version)
                                       .replace("{platform}", platform)
                                       .replace("{tag}", tag);
                return new URL(value);
            }
        }
    }
}
