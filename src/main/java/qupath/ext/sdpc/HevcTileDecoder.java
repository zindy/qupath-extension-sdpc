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
import java.awt.image.DataBufferByte;
import java.io.IOException;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Decodes single-frame HEVC bitstreams (as stored in sdpc tiles) to RGB images using FFmpeg.
 * Instances are cheap and not thread-safe; every call allocates and releases its own native state.
 */
final class HevcTileDecoder {

	// Must run before any FFmpeg class is initialised (see FfmpegNatives)
	static {
		FfmpegNatives.ensureLoaded();
	}

	private HevcTileDecoder() {}

	static BufferedImage decode(byte[] data) throws IOException {
		AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_HEVC);
		if (codec == null)
			throw new IOException("FFmpeg HEVC decoder not available");
		AVCodecContext ctx = avcodec_alloc_context3(codec);
		AVPacket packet = av_packet_alloc();
		AVFrame frame = av_frame_alloc();
		SwsContext sws = null;
		try {
			if (ctx == null || packet == null || frame == null)
				throw new IOException("Unable to allocate FFmpeg structures");
			check(avcodec_open2(ctx, codec, (PointerPointer<?>) null), "open HEVC decoder");

			// av_new_packet adds the zero padding that the decoder expects
			check(av_new_packet(packet, data.length), "allocate packet");
			packet.data().put(data, 0, data.length);
			check(avcodec_send_packet(ctx, packet), "send packet");
			// Flush, because there is only ever one frame per tile
			avcodec_send_packet(ctx, null);
			int ret = avcodec_receive_frame(ctx, frame);
			check(ret, "decode tile");

			int w = frame.width();
			int h = frame.height();
			sws = sws_getContext(w, h, frame.format(), w, h, AV_PIX_FMT_BGR24,
					SWS_BILINEAR, null, null, (double[]) null);
			if (sws == null)
				throw new IOException("Unable to create color conversion for pixel format " + frame.format());

			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
			byte[] pixels = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
			try (BytePointer dst = new BytePointer(pixels.length);
				 IntPointer dstStride = new IntPointer(1).put(w * 3);
				 PointerPointer<?> dstData = new PointerPointer<>(1).put(0, dst)) {
				sws_scale(sws, frame.data(), frame.linesize(), 0, h, dstData, dstStride);
				dst.get(pixels);
			}
			return img;
		} finally {
			if (sws != null)
				sws_freeContext(sws);
			if (frame != null)
				av_frame_free(new PointerPointer<>(1).put(0, frame));
			if (packet != null)
				av_packet_free(new PointerPointer<>(1).put(0, packet));
			if (ctx != null)
				avcodec_free_context(new PointerPointer<>(1).put(0, ctx));
		}
	}

	private static void check(int code, String action) throws IOException {
		if (code < 0)
			throw new IOException("FFmpeg failed to " + action + " (error " + code + ")");
	}
}
