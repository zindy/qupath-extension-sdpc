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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

/**
 * An {@link qupath.lib.images.servers.ImageServer} for TEKSQRAY {@code .sdpc} slides.
 */
public class SdpcImageServer extends AbstractTileableImageServer {

	private static final Logger logger = LoggerFactory.getLogger(SdpcImageServer.class);

	private final URI uri;
	private final String[] args;
	private final SdpcFile sdpc;
	private final ImageServerMetadata originalMetadata;
	private final Map<String, BufferedImage> associatedImages = new LinkedHashMap<>();
	private final AtomicBoolean closed = new AtomicBoolean(false);

	SdpcImageServer(URI uri, String... args) throws IOException {
		super();
		this.uri = uri;
		this.args = args;
		Path path = Paths.get(uri);
		sdpc = new SdpcFile(path);
		try {
			if (sdpc.getSliceFormat() != SdpcFile.HEVC_SLICE_FORMAT)
				throw new IOException("Unsupported sdpc tile format " + sdpc.getSliceFormat() + " (only HEVC is supported)");

			var builder = new ImageServerMetadata.Builder(SdpcImageServer.class, uri.toString(),
							sdpc.getWidth(), sdpc.getHeight())
					.width(sdpc.getWidth())
					.height(sdpc.getHeight())
					.rgb(true)
					.pixelType(PixelType.UINT8)
					.preferredTileSize(sdpc.getTileWidth(), sdpc.getTileHeight())
					.name(path.getFileName().toString());
			if (sdpc.getMpp() > 0 && Double.isFinite(sdpc.getMpp()))
				builder.pixelSizeMicrons(sdpc.getMpp(), sdpc.getMpp());
			if (sdpc.getMagnification() > 0)
				builder.magnification(sdpc.getMagnification());
			var levels = new ImageServerMetadata.ImageResolutionLevel.Builder(sdpc.getWidth(), sdpc.getHeight());
			for (var layer : sdpc.getLayers()) {
				double downsample = 1.0 / layer.scale();
				// Layers are padded to whole tiles, so the real size comes from the full resolution size
				int w = Math.max(1, (int) Math.min(Math.round(sdpc.getWidth() * layer.scale()),
						(long) layer.tilesX() * sdpc.getTileWidth()));
				int h = Math.max(1, (int) Math.min(Math.round(sdpc.getHeight() * layer.scale()),
						(long) layer.tilesY() * sdpc.getTileHeight()));
				levels.addLevel(downsample, w, h);
			}
			builder.levels(levels.build());
			originalMetadata = builder.build();
			loadAssociatedImages();
		} catch (IOException | RuntimeException e) {
			sdpc.close();
			throw e;
		}
	}

	private void loadAssociatedImages() {
		try {
			List<byte[]> jpegs = sdpc.readEmbeddedJpegs();
			// Sdpc files store the label image followed by the slide overview
			List<String> names = jpegs.size() == 2 ? List.of("Label", "Macro") : null;
			for (int i = 0; i < jpegs.size(); i++) {
				try {
					BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegs.get(i)));
					if (img != null)
						associatedImages.put(names == null ? "Image " + (i + 1) : names.get(i), img);
				} catch (IOException e) {
					logger.debug("Unable to read embedded image {}: {}", i, e.getMessage());
				}
			}
		} catch (IOException e) {
			logger.warn("Unable to read associated images: {}", e.getMessage());
		}
	}

	@Override
	protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
		int level = tileRequest.getLevel();
		int tx = tileRequest.getTileX() / sdpc.getTileWidth();
		int ty = tileRequest.getTileY() / sdpc.getTileHeight();
		BufferedImage decoded = HevcTileDecoder.decode(sdpc.readTileBytes(level, tx, ty));
		int w = tileRequest.getTileWidth();
		int h = tileRequest.getTileHeight();
		if (decoded.getWidth() == w && decoded.getHeight() == h)
			return decoded;
		// Edge tiles are padded in the file
		BufferedImage cropped = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
		var g = cropped.createGraphics();
		try {
			g.drawImage(decoded, 0, 0, null);
		} finally {
			g.dispose();
		}
		return cropped;
	}

	@Override
	protected ServerBuilder<BufferedImage> createServerBuilder() {
		return DefaultImageServerBuilder.createInstance(SdpcServerBuilder.class, getMetadata(), uri, args);
	}

	@Override
	protected String createID() {
		return getClass().getName() + ": " + uri.toString() + " " + Arrays.toString(args);
	}

	@Override
	public Collection<URI> getURIs() {
		return List.of(uri);
	}

	@Override
	public String getServerType() {
		return "SDPC (FFmpeg HEVC)";
	}

	@Override
	public ImageServerMetadata getOriginalMetadata() {
		return originalMetadata;
	}

	@Override
	public List<String> getAssociatedImageList() {
		return new ArrayList<>(associatedImages.keySet());
	}

	@Override
	public BufferedImage getAssociatedImage(String name) {
		return associatedImages.get(name);
	}

	@Override
	public void close() throws Exception {
		if (closed.compareAndSet(false, true)) {
			super.close();
			sdpc.close();
		}
	}
}
