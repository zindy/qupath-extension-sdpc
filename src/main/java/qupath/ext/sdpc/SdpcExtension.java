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

import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension adding support for TEKSQRAY (.sdpc) whole slide images.
 * <p>
 * The actual work is done by {@link SdpcServerBuilder}, which is registered through
 * {@code META-INF/services/qupath.lib.images.servers.ImageServerBuilder}.
 */
public class SdpcExtension implements QuPathExtension {

	private static final Logger logger = LoggerFactory.getLogger(SdpcExtension.class);

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.sdpc.ui.strings");

	private static final String EXTENSION_NAME = resources.getString("name");
	private static final String EXTENSION_DESCRIPTION = resources.getString("description");
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

	@Override
	public void installExtension(QuPathGUI qupath) {
		logger.info("{} installed: .sdpc files can now be opened", EXTENSION_NAME);
	}

	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}

	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}
}
