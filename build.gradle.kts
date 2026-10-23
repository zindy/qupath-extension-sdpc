plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

// Get version from Environment Variable (GitHub Actions) or fallback to VERSION file
// 1. Get the tag name from GitHub (e.g., "v1.0.3" or "v1.0.3-rc1")
val githubTag = System.getenv("GITHUB_REF_NAME")

// 2. Determine the final version string
val releaseVersion = if (githubTag != null && githubTag.startsWith("v")) {
    githubTag.removePrefix("v") // Use the tag (stripped of 'v')
} else {
    file("VERSION").readText().trim() // Fallback to your SNAPSHOT file
}

qupathExtension {
    name = "qupath-extension-sdpc"
    group = "io.github.qupath"
    version = releaseVersion

    description = "Adds support for opening TEKSQRAY (.sdpc) whole slide images in QuPath."
    automaticModule = "io.github.qupath.extension.sdpc"
}

// sdpc tiles are HEVC frames, decoded with the (LGPL) FFmpeg build from JavaCPP presets.
// Must stay in line with the JavaCPP version used by QuPath's OpenCV.
val ffmpegVersion = "7.1.1-1.5.12"

dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)

    // Java API, plus native libraries for every platform QuPath supports
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    for (platform in listOf("windows-x86_64", "linux-x86_64", "macosx-x86_64", "macosx-arm64"))
        runtimeOnly("org.bytedeco:ffmpeg:$ffmpegVersion:$platform")

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

}

// Only the decoder (avcodec), pixel conversion (swscale) and their dependencies are needed.
tasks.shadowJar {
    exclude("org/bytedeco/ffmpeg/*/*avformat*", "org/bytedeco/ffmpeg/*/*avdevice*",
        "org/bytedeco/ffmpeg/*/*avfilter*", "org/bytedeco/ffmpeg/*/*postproc*",
        "org/bytedeco/ffmpeg/*/ffmpeg*", "org/bytedeco/ffmpeg/*/ffprobe*")
}

