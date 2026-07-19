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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
        private static final Set<String> loaded_ = new HashSet<String>();
        private static Path libraryPath_;
        private static boolean runtimeResolveAttempted_ = false;
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

            System.load(key);
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

            Path root = cacheRoot().resolve(platform);
            try (InputStream in = openResource(manifestResource)) {
                if (in == null) return null;

                List<String> entries = readManifest(in);
                Files.createDirectories(root);
                for (String entry : entries) {
                    if (entry.length() == 0 || entry.startsWith("#")) continue;
                    extractEntry(platform, root, entry);
                }
            } catch (IOException e) {
                UnsatisfiedLinkError error = new UnsatisfiedLinkError(
                        "Failed to extract bundled JCEF native runtime: " + e.getMessage());
                error.initCause(e);
                throw error;
            }

            libraryPath_ = macLibraryPath(root);
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

            Path cache = cacheRoot();
            Path root = cache.resolve(platform);
            Path marker = root.resolve(".jcef-runtime-complete");
            if (Files.isRegularFile(marker) && containsRuntimeLibrary(root)) {
                libraryPath_ = macLibraryPath(root);
                return libraryPath_;
            }

            Path zipPath = cache.resolve("jcef-runtime-" + platform + "-" + version + ".zip");
            try {
                Files.createDirectories(cache);
                download(url, zipPath, platform);
                extractZip(zipPath, root);
                Files.write(marker, ("url=" + url + "\n").getBytes("UTF-8"));
                libraryPath_ = macLibraryPath(root);
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
        }

        private static void extractZip(Path zipPath, Path root) throws IOException {
            Files.createDirectories(root);
            try (ZipInputStream zip = new ZipInputStream(
                         new BufferedInputStream(Files.newInputStream(zipPath)))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path target = root.resolve(entry.getName()).normalize();
                    if (!target.startsWith(root)) {
                        throw new IOException("Invalid runtime zip entry: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                        applyPermissions(target);
                    }
                    zip.closeEntry();
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

        private static Path cacheRoot() {
            String override = System.getProperty("jcef.orion.cache.path");
            if (override != null && override.length() > 0) return Paths.get(override);

            String home = System.getProperty("user.home");
            if (home == null || home.length() == 0) {
                return Paths.get(
                        System.getProperty("java.io.tmpdir"), "jcef-orion", cacheVersion());
            }
            return Paths.get(home, ".jcef-orion", cacheVersion());
        }

        private static String cacheVersion() {
            Package pkg = SystemBootstrap.class.getPackage();
            String version = pkg == null ? null : pkg.getImplementationVersion();
            if (version == null || version.length() == 0) return DEFAULT_CACHE_VERSION;
            return version;
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
