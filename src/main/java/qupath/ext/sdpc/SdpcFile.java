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

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal reader for the container structure of TEKSQRAY {@code .sdpc} whole slide images.
 * <p>
 * Layout (little endian):
 * <pre>
 *   SqPicHead (156 bytes) ... macro / label JPEGs ...
 *   per layer: 'IF' SqPicInfo (122 bytes) | uint32 sliceSize[sliceNum] | HEVC tile bitstreams
 * </pre>
 * {@code SqPicInfo.nextLayerOffset} points at the next layer's block.
 * Each tile is a single HEVC intra frame of the full tile size (edge tiles are padded).
 */
final class SdpcFile implements Closeable {

	static final int HEVC_SLICE_FORMAT = 4;

	private static final int HEAD_SIZE = 156;
	private static final int PIC_INFO_SIZE = 122;
	private static final int SCAN_CHUNK = 1 << 20;

	/** Information about one pyramid layer. */
	record Layer(int index, int tilesX, int tilesY, double scale, long[] offsets, int[] sizes, long nextOffset) {
		int tileCount() {
			return sizes.length;
		}
	}

	private final FileChannel channel;
	private final int tileWidth;
	private final int tileHeight;
	private final int width;
	private final int height;
	private final int magnification;
	private final double mpp;
	private final int sliceFormat;
	private final long fileSize;
	private final List<Layer> layers;
	private final long firstTileOffset;

	SdpcFile(Path path) throws IOException {
		channel = FileChannel.open(path, StandardOpenOption.READ);
		try {
			fileSize = channel.size();
			ByteBuffer head = readFully(0, HEAD_SIZE);
			if (head.get(0) != 'S' || head.get(1) != 'Q')
				throw new IOException(path + " is not an sdpc file");
			// flag(2) version(16) headSize(4) fileSize(8) macrograph(4) personInfor(4)
			int hierarchy = head.getInt(38);
			width = head.getInt(42);
			height = head.getInt(46);
			tileWidth = head.getInt(50);
			tileHeight = head.getInt(54);
			// thumbnailWidth(4) thumbnailHeight(4) bpp(1) quality(1) colrSpace(4) scale(4)
			mpp = head.getDouble(76);
			magnification = head.getInt(84);
			// extraOffset(8) tileOffset(8)
			sliceFormat = head.get(104) & 0xff;
			if (tileWidth <= 0 || tileHeight <= 0 || width <= 0 || height <= 0)
				throw new IOException("Invalid sdpc header in " + path);
			layers = findLayers(hierarchy);
			firstTileOffset = layers.get(0).offsets()[0];
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	private List<Layer> findLayers(int hierarchy) throws IOException {
		int nx0 = ceilDiv(width, tileWidth);
		int ny0 = ceilDiv(height, tileHeight);
		Layer first = null;
		// Look for the first 'IF' block whose grid matches the full-resolution image
		long pos = HEAD_SIZE;
		while (first == null && pos < fileSize - PIC_INFO_SIZE) {
			int len = (int) Math.min(SCAN_CHUNK + 6, fileSize - pos);
			ByteBuffer buf = readFully(pos, len);
			int limit = len - 6;
			for (int i = 0; i < limit; i++) {
				if (buf.get(i) == 'I' && buf.get(i + 1) == 'F' && buf.getInt(i + 2) == PIC_INFO_SIZE) {
					Layer candidate = tryReadLayer(pos + i);
					if (candidate != null && candidate.tilesX() == nx0 && candidate.tilesY() == ny0
							&& candidate.tileCount() == nx0 * ny0) {
						first = candidate;
						break;
					}
				}
			}
			pos += SCAN_CHUNK;
		}
		if (first == null)
			throw new IOException("Could not locate the full-resolution tile index");

		List<Layer> result = new ArrayList<>();
		result.add(first);
		Layer current = first;
		while (result.size() < hierarchy && current.nextOffset() > 0) {
			Layer next = tryReadLayer(current.nextOffset());
			if (next == null)
				break;
			result.add(next);
			current = next;
		}
		return result;
	}

	private Layer tryReadLayer(long offset) throws IOException {
		if (offset < 0 || offset + PIC_INFO_SIZE > fileSize)
			return null;
		ByteBuffer b = readFully(offset, PIC_INFO_SIZE);
		if (b.get(0) != 'I' || b.get(1) != 'F' || b.getInt(2) != PIC_INFO_SIZE)
			return null;
		int index = b.getInt(6);
		int n = b.getInt(10);
		int nx = b.getInt(14);
		int ny = b.getInt(18);
		long nextOffset = b.getLong(30);
		double scale = b.getFloat(38);
		if (n <= 0 || nx <= 0 || ny <= 0 || (long) nx * ny != n)
			return null;
		long sizesStart = offset + PIC_INFO_SIZE;
		if (sizesStart + 4L * n > fileSize)
			return null;
		ByteBuffer sizeBuf = readFully(sizesStart, 4 * n);
		int[] sizes = new int[n];
		long[] offsets = new long[n];
		long cursor = sizesStart + 4L * n;
		for (int i = 0; i < n; i++) {
			sizes[i] = sizeBuf.getInt(4 * i);
			offsets[i] = cursor;
			cursor += sizes[i] & 0xffffffffL;
		}
		if (cursor > fileSize)
			return null;
		return new Layer(index, nx, ny, scale, offsets, sizes, nextOffset);
	}

	private ByteBuffer readFully(long position, int length) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
		while (buf.hasRemaining()) {
			int n = channel.read(buf, position + buf.position());
			if (n < 0)
				throw new IOException("Unexpected end of file");
		}
		buf.flip();
		return buf;
	}

	/** Raw HEVC bitstream for one tile. */
	byte[] readTileBytes(int layer, int tx, int ty) throws IOException {
		Layer lay = layers.get(layer);
		if (tx < 0 || ty < 0 || tx >= lay.tilesX() || ty >= lay.tilesY())
			throw new IOException("Tile out of range: " + tx + ", " + ty);
		int i = ty * lay.tilesX() + tx;
		ByteBuffer buf = readFully(lay.offsets()[i], lay.sizes()[i]);
		return buf.array();
	}

	/** Embedded JPEG images (label / macro) stored between the header and the first tile. */
	List<byte[]> readEmbeddedJpegs() throws IOException {
		List<byte[]> out = new ArrayList<>();
		int len = (int) Math.min(firstTileOffset - HEAD_SIZE, Integer.MAX_VALUE - 16);
		if (len <= 0)
			return out;
		byte[] data = readFully(HEAD_SIZE, len).array();
		int pos = 0;
		while (true) {
			int s = indexOf(data, (byte) 0xff, (byte) 0xd8, (byte) 0xff, pos);
			if (s < 0)
				return out;
			int e = indexOf(data, (byte) 0xff, (byte) 0xd9, null, s);
			if (e < 0)
				return out;
			byte[] jpeg = new byte[e + 2 - s];
			System.arraycopy(data, s, jpeg, 0, jpeg.length);
			out.add(jpeg);
			pos = e + 2;
		}
	}

	private static int indexOf(byte[] data, byte a, byte b, Byte c, int from) {
		int last = data.length - (c == null ? 2 : 3);
		for (int i = from; i <= last; i++) {
			if (data[i] == a && data[i + 1] == b && (c == null || data[i + 2] == c))
				return i;
		}
		return -1;
	}

	private static int ceilDiv(int a, int b) {
		return (a + b - 1) / b;
	}

	int getWidth() {
		return width;
	}

	int getHeight() {
		return height;
	}

	int getTileWidth() {
		return tileWidth;
	}

	int getTileHeight() {
		return tileHeight;
	}

	/** Micrometers per pixel at full resolution. */
	double getMpp() {
		return mpp;
	}

	int getMagnification() {
		return magnification;
	}

	int getSliceFormat() {
		return sliceFormat;
	}

	List<Layer> getLayers() {
		return layers;
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
