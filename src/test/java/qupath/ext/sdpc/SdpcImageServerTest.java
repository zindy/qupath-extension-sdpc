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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import qupath.lib.regions.RegionRequest;

/**
 * Runs against a real slide, given by the {@code sdpc.test.file} system property or the
 * {@code SDPC_TEST_FILE} environment variable. Skipped if there is none.
 * Expected values come from the pure Python reader (opensdpc sample 20220514_145829_0.sdpc).
 */
class SdpcImageServerTest {

	private static Path testFile() {
		String p = System.getProperty("sdpc.test.file", System.getenv("SDPC_TEST_FILE"));
		Assumptions.assumeTrue(p != null && Files.isRegularFile(Paths.get(p)), "No sdpc test file");
		return Paths.get(p);
	}

	@Test
	void readsMetadataAndPixels() throws Exception {
		Path path = testFile();
		var builder = new SdpcServerBuilder();
		var support = builder.checkImageSupport(path.toUri());
		assertTrue(support.getSupportLevel() > 0);

		try (var server = builder.buildServer(path.toUri())) {
			assertEquals(26880, server.getWidth());
			assertEquals(21504, server.getHeight());
			assertEquals(4, server.nResolutions());
			assertEquals(4.0, server.getDownsampleForResolution(1), 1e-6);
			assertEquals(0.1045, server.getPixelCalibration().getAveragedPixelSizeMicrons(), 1e-3);
			assertEquals(2, server.getAssociatedImageList().size());

			BufferedImage tile = server.readRegion(RegionRequest.createInstance(
					server.getPath(), 1.0, 20 * 672, 16 * 672, 672, 672));
			assertEquals(672, tile.getWidth());
			assertMean(new double[]{156.47, 114.67, 166.17}, mean(tile), 1.5);

			BufferedImage thumb = server.readRegion(RegionRequest.createInstance(
					server.getPath(), 64.0, 0, 0, server.getWidth(), server.getHeight()));
			assertEquals(420, thumb.getWidth());
			assertEquals(336, thumb.getHeight());
			assertMean(new double[]{200.02, 177.82, 200.47}, mean(thumb), 1.5);
		}
	}

	private static void assertMean(double[] expected, double[] actual, double tol) {
		for (int i = 0; i < 3; i++)
			assertEquals(expected[i], actual[i], tol, "channel " + i);
	}

	private static double[] mean(BufferedImage img) {
		double r = 0, g = 0, b = 0;
		for (int j = 0; j < img.getHeight(); j++)
			for (int i = 0; i < img.getWidth(); i++) {
				int rgb = img.getRGB(i, j);
				r += (rgb >> 16) & 0xff;
				g += (rgb >> 8) & 0xff;
				b += rgb & 0xff;
			}
		double n = (double) img.getWidth() * img.getHeight();
		return new double[]{r / n, g / n, b / n};
	}
}
