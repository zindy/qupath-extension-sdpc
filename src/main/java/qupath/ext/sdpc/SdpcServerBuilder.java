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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

/**
 * Registers {@code .sdpc} support with QuPath's image server provider mechanism.
 */
public class SdpcServerBuilder implements ImageServerBuilder<BufferedImage> {

	private static final Logger logger = LoggerFactory.getLogger(SdpcServerBuilder.class);

	@Override
	public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) throws IOException {
		float support = supportLevel(uri);
		if (support <= 0f)
			return null;
		return UriImageSupport.createInstance(getClass(), support,
				DefaultImageServerBuilder.createInstance(getClass(), uri, args));
	}

	private static float supportLevel(URI uri) {
		if (!"file".equalsIgnoreCase(uri.getScheme()))
			return 0f;
		Path path;
		try {
			path = Paths.get(uri);
		} catch (RuntimeException e) {
			return 0f;
		}
		String name = path.getFileName().toString().toLowerCase();
		if (!name.endsWith(".sdpc") || !Files.isRegularFile(path))
			return 0f;
		// Check the magic bytes; a bit higher than Bio-Formats or OpenSlide would give an unknown format
		try (var in = Files.newInputStream(path)) {
			if (in.read() == 'S' && in.read() == 'Q')
				return 4f;
		} catch (IOException e) {
			logger.debug("Unable to read {}: {}", path, e.getMessage());
		}
		return 0f;
	}

	@Override
	public ImageServer<BufferedImage> buildServer(URI uri, String... args) throws Exception {
		return new SdpcImageServer(uri, args);
	}

	@Override
	public String getName() {
		return "SDPC";
	}

	@Override
	public String getDescription() {
		return "Reads TEKSQRAY .sdpc whole slide images (HEVC tiles decoded with FFmpeg)";
	}

	@Override
	public Class<BufferedImage> getImageType() {
		return BufferedImage.class;
	}
}
