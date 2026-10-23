/*
 * QuPath extension for reading .sdpc HEVC-compressed WSI files using FFmpeg.
 * Copyright (C) 2026 Egor Zindy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package qupath.ext.sdpc;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the bundled FFmpeg native libraries from this extension's own class loader.
 * <p>
 * Why: JavaCPP's {@code Loader} lives in QuPath's class loader, whereas the FFmpeg classes live in the
 * extension class loader. When those differ, JavaCPP reflects into {@code Runtime.load0} /
 * {@code Runtime.loadLibrary0}, which throws {@code InaccessibleObjectException} unless the JVM is started with
 * {@code --add-opens=java.base/java.lang=ALL-UNNAMED}.
 * <p>
 * Instead we call {@link System#load(String)} ourselves from a class of this extension (so the libraries
 * are bound to the extension class loader, exactly as {@code load0} would do), and then register the
 * libraries in JavaCPP's private {@code Loader.loadedLibraries} map so that JavaCPP considers them
 * loaded and skips its own attempt. Same trick as QuPath's JPen extension, applied to JavaCPP.
 * <p>
 * Two details matter:
 * <ul>
 * <li>JavaCPP also asks for the "link" names (e.g. {@code avutil@.59}). On Windows those do not exist as files,
 *     JavaCPP normally lets that fail quietly, but via the reflective fallback it throws instead, so we register
 *     them too.</li>
 * <li>The JNI_OnLoad of each library calls back into {@code Loader.putMemberOffset}, which resolves classes using
 *     the top of JavaCPP's private {@code classStack}. We push a class from this extension while loading,
 *     as {@code Loader.loadLibrary} itself would.</li>
 * </ul>
 * Names below come from {@code Loader.getLoadedLibraries()} and the ffmpeg presets for 7.1.1-1.5.12 on Windows x64.
 * They must be updated when the FFmpeg version changes (library suffixes -59, -5, -61, -8).
 * Other platforms are not handled yet: this does nothing there and JavaCPP's own loader is used.
 */
final class FfmpegNatives {

	private static final Logger logger = LoggerFactory.getLogger(FfmpegNatives.class);

	private static final String WINDOWS_PLATFORM = "windows-x86_64";

	/** {file name in the jar, key used by JavaCPP, optional extra "link" key}; dependencies first. */
	private static final String[][] WINDOWS_LIBS = {
			{"avutil-59.dll", "avutil-59", "avutil@.59"},
			{"jniavutil.dll", "jniavutil"},
			{"swresample-5.dll", "swresample-5", "swresample@.5"},
			{"jniswresample.dll", "jniswresample"},
			{"avcodec-61.dll", "avcodec-61", "avcodec@.61"},
			{"jniavcodec.dll", "jniavcodec"},
			{"swscale-8.dll", "swscale-8", "swscale@.8"},
			{"jniswscale.dll", "jniswscale"},
	};

	/** Any class from this extension's FFmpeg bindings; used as class-loading context for JNI_OnLoad. */
	private static final String CONTEXT_CLASS = "org.bytedeco.ffmpeg.global.avutil";

	private static boolean attempted = false;

	private FfmpegNatives() {}

	/**
	 * Preload the natives if possible. Never throws: on failure JavaCPP's own loader is left to try
	 * (which works when QuPath is started with the --add-opens flag).
	 */
	static synchronized void ensureLoaded() {
		if (attempted)
			return;
		attempted = true;
		if (!isWindowsX64()) {
			logger.debug("FFmpeg preloading only implemented for {}; using JavaCPP's loader", WINDOWS_PLATFORM);
			return;
		}
		try {
			preload();
		} catch (Throwable t) {
			logger.warn("Unable to preload FFmpeg natives ({}); falling back to JavaCPP's loader, which needs "
					+ "--add-opens=java.base/java.lang=ALL-UNNAMED", t.toString());
			logger.debug(t.getMessage(), t);
		}
	}

	private static boolean isWindowsX64() {
		var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"));
	}

	@SuppressWarnings("unchecked")
	private static void preload() throws Exception {
		// Make sure JavaCPP itself (and jnijavacpp) is initialised first, since the jni* libraries depend on it
		Class<?> loader = Class.forName("org.bytedeco.javacpp.Loader");
		Class.forName("org.bytedeco.javacpp.Pointer");

		// JavaCPP is in the unnamed module, so reflecting into it does not need --add-opens
		Field registryField = loader.getDeclaredField("loadedLibraries");
		registryField.setAccessible(true);
		Map<String, String> registry = (Map<String, String>) registryField.get(null);

		Field stackField = loader.getDeclaredField("classStack");
		stackField.setAccessible(true);
		Deque<Class<?>> classStack = ((ThreadLocal<Deque<Class<?>>>) stackField.get(null)).get();

		ClassLoader cl = FfmpegNatives.class.getClassLoader();
		Class<?> context = Class.forName(CONTEXT_CLASS, false, cl); // no initialisation

		synchronized (loader) { // Loader.loadLibrary is static synchronized
			if (registry.containsKey(WINDOWS_LIBS[0][1])) {
				logger.debug("FFmpeg natives already registered with JavaCPP");
				return;
			}
		}

		Path dir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "qupath-sdpc-ffmpeg"));
		for (String[] lib : WINDOWS_LIBS) {
			Path file = extract(cl, "org/bytedeco/ffmpeg/" + WINDOWS_PLATFORM + "/" + lib[0], dir.resolve(lib[0]));
			String abs = file.toAbsolutePath().toString();
			logger.debug("Loading {}", abs);
			synchronized (loader) {
				classStack.push(context);
				try {
					System.load(abs); // caller is in the extension class loader, so the library binds to it
				} finally {
					classStack.pop();
				}
				for (int i = 1; i < lib.length; i++)
					registry.put(lib[i], abs);
			}
		}
		logger.debug("FFmpeg natives loaded and registered with JavaCPP");
	}

	private static Path extract(ClassLoader cl, String resource, Path target) throws IOException {
		byte[] bytes;
		try (InputStream in = cl.getResourceAsStream(resource)) {
			if (in == null)
				throw new IOException("Missing resource " + resource);
			bytes = in.readAllBytes();
		}
		// Windows keeps loaded DLLs locked (e.g. a second QuPath instance), so only write when needed
		if (Files.isRegularFile(target) && Files.size(target) == bytes.length)
			return target;
		try {
			Files.write(target, bytes);
		} catch (IOException e) {
			if (!Files.isRegularFile(target))
				throw e;
			logger.debug("Could not overwrite {} ({}); using existing file", target, e.toString());
		}
		return target;
	}
}
