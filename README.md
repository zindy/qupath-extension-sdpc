# QuPath SDPC extension

Adds support for opening `.sdpc` whole slide images in [QuPath](https://qupath.github.io) (v0.7.0+).
`.sdpc` is the slide format of the digital pathology scanners and viewer software from
Shenzhen Shengqiang Technology Co., Ltd. (SQRAY, sold under the TEKSQRAY name; [sqray.com](https://www.sqray.com)).

Drag a `.sdpc` file onto QuPath, or add it to a project, like any other image. Nothing is converted or written to disk.

> This extension is an independent, community project. It is not affiliated with or endorsed by Shenzhen Shengqiang
> Technology Co., Ltd. The file format is not publicly documented by the vendor; support is based on the work credited below.

<img width="1538" height="960" alt="image" src="https://github.com/user-attachments/assets/2c745309-5289-45a9-938c-f1553a249d03" />

## What you get

- The native pyramid levels of the slide, read on demand.
- Pixel size and scan magnification from the file header.
- The embedded label and macro images, as associated images.
- No intermediate TIFF/SVS conversion step.

## Scanners and slides

The `.sdpc` format is produced by the vendor's scanners and software. This README deliberately does not list
supported scanner models: I could not find an authoritative list of which models write `.sdpc`, and the extension
has so far been tested on very few slides (below). If it works (or fails) on your scanner, please open an issue
and say which model and software version wrote the file.

| Slide | Source | Scan | Result |
|---|---|---|---|
| [20220514_145829_0.sdpc](https://github.com/WonderLandxD/opensdpc/raw/refs/heads/main/data/20220514_145829_0.sdpc) | sample slide shipped with [opensdpc](https://github.com/WonderLandxD/opensdpc) | 40x, 4 levels, 26880 x 21504 px at level 0 | Pixels compared against the Python reference reader |

Only HEVC-compressed slides (`sliceFormat == 4`) are supported. Other compression variants, if they exist, are rejected
with an error message.

## Installation

1. Download `qupath-extension-sdpc-<version>-all.jar` from the releases page.
2. Close QuPath, then copy the jar into QuPath's extensions folder (or drag it onto the QuPath window).
3. Restart QuPath.

The jar bundles the FFmpeg decoder libraries for Windows, Linux and macOS (Intel and Apple silicon).

### Platform status

| Platform | Status |
|---|---|
| Windows x64 | Tested. The extension loads its bundled FFmpeg libraries itself; no extra setup. |
| Linux | Tested. But will not work without the export JAVA_TOOL_OPTIONS below before launching QuPath |
| macOS | Not tested. JavaCPP's own library loader is used, which fails on Java 16+ unless QuPath is started with `--add-opens=java.base/java.lang=ALL-UNNAMED` (see below). |

Why the flag: JavaCPP loads native libraries on behalf of classes from other class loaders by reflecting into
`java.lang.Runtime`, which recent Java versions block. On Windows the extension works around this by loading and
registering the libraries itself (`FfmpegNatives`). That workaround has not been written for other platforms yet.

To pass the flag, set the environment variable before starting QuPath:

```bat
set JAVA_TOOL_OPTIONS=--add-opens=java.base/java.lang=ALL-UNNAMED
```

or in bash (for Linux):
```bash
export JAVA_TOOL_OPTIONS="--add-opens=java.base/java.lang=ALL-UNNAMED"
```

or add a `java-options=--add-opens=java.base/java.lang=ALL-UNNAMED` line to the `[JavaOptions]` section of the
QuPath launcher `.cfg` file, if your installation has one.

## How it works

An `.sdpc` file is a container holding a pyramid of independently compressed tiles, each a single HEVC (H.265) intra
frame. The extension registers an `ImageServerBuilder` that:

- parses the file header and the per-layer tile index (pure Java, `SdpcFile`),
- decodes tiles on demand with FFmpeg's HEVC decoder via
  [JavaCPP](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg) (`HevcTileDecoder`),
- exposes the native pyramid levels, pixel size, magnification, and the embedded label/macro images as associated
  images.

Layout, as reverse-engineered by the projects below: a fixed-size header, the embedded label/macro JPEGs, then for
each pyramid layer an `IF` info block, a table of tile sizes, and the HEVC bitstreams, with each layer's info block
pointing at the next.

## Limitations

- HEVC-compressed slides only.
- Tested on Windows x64 and on a small number of slides.
- Read-only. Annotations or other data stored by the vendor software, if any, are not read.
- Decoding is done in software; very large slides may feel slower than with formats QuPath reads natively.

## Build

```bash
gradlew shadowJar
```

The jar is written to `build/libs/qupath-extension-sdpc-<version>-all.jar`.

## Tests

`SdpcImageServerTest` compares decoded pixels to values from the Python reader. It needs a sample slide
(e.g. `data/20220514_145829_0.sdpc` from opensdpc):

```bash
set SDPC_TEST_FILE=path/to/20220514_145829_0.sdpc
gradlew test
```

Without the variable the test is skipped. The sample slide is not part of this repository.

## Acknowledgements

The file format is not documented by the vendor. This extension would not exist without:

- **[opensdpc](https://github.com/WonderLandxD/opensdpc)** by Jiawen Li (H&G Pathology AI Research Team), a Python
  library for reading `.sdpc` slides, whose structure definitions and sample slide were the starting point for the
  file layout.
- **The [image.sc forum](https://forum.image.sc/t/inquiry-about-support-for-reading-sdpc-format-slides-in-qupath/88317/9)** discussion on reading `.sdpc` slides in QuPath, and the pure-Python
  `sdpc_reader.py` / `sdpc2tiff.py` shared there by Yaoting Sun, which showed that the tiles can be decoded with a standard HEVC
  decoder and gave reference pixel values to test against.
- **[FFmpeg](https://ffmpeg.org)**, through the **[JavaCPP Presets](https://github.com/bytedeco/javacpp-presets)**
  by Samuel Audet and contributors, for HEVC decoding and colour conversion.
- **[QuPath](https://qupath.github.io)** by Pete Bankhead and the QuPath developers, including the extension
  template, and its JPen extension, whose approach to loading native libraries from an extension jar inspired
  `FfmpegNatives`.

## License

Copyright (c) the qupath-extension-sdpc authors. Licensed under the GNU General Public License v3.0; see `LICENSE`.

The jar redistributes, unmodified, third-party components under their own licenses:

| Component | License |
|---|---|
| FFmpeg (the non-GPL build from the JavaCPP Presets) | LGPL-2.1-or-later |
| JavaCPP and JavaCPP Presets | Apache-2.0 (or GPL-2.0 with Classpath Exception) |

FFmpeg source code is available from [ffmpeg.org](https://ffmpeg.org) and the
[JavaCPP Presets](https://github.com/bytedeco/javacpp-presets) repository.
"TEKSQRAY" and "SQRAY" are trademarks of their owner and are used here only to identify the file format's origin.
