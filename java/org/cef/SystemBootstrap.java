// Copyright (c) 2020 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
package org.cef;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * To allow customization of System.load() calls by supplying a different
 * implementation.  You'll want to call <code>setLoader</code> with your custom
 * implementation before calling into any other CEF classes which then in turn
 * will start triggering libraries to be loaded at runtime.
 */
public class SystemBootstrap {
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

    private static final class EmbeddedNativeLoader {
        private static final String RESOURCE_ROOT = "org/cef/native";
        private static final String DEFAULT_CACHE_VERSION = "1.0.0";
        private static final Set<String> loaded_ = new HashSet<String>();
        private static Path libraryPath_;
        private static boolean extractionAttempted_ = false;

        static synchronized boolean loadLibrary(String libname) {
            String filename = mapLibraryName(libname);
            if (filename == null) return false;

            Path root = ensureExtracted();
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
            Path root = ensureExtracted();
            return root == null ? null : root.toAbsolutePath().normalize().toString();
        }

        private static Path ensureExtracted() {
            if (libraryPath_ != null) return libraryPath_;
            if (extractionAttempted_) return null;
            extractionAttempted_ = true;

            String platform = platformName();
            if (platform == null) return null;

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
    }
}
