package com.travelguide.anywhere.service

/**
 * Catalog of Piper TTS voices available for download from the sherpa-onnx tts-models
 * GitHub release. Each voice is a self-contained tar.bz2 (~60-110 MB) that extracts to
 * a directory containing the .onnx model, its config json, tokens.txt, and espeak-ng-data.
 *
 * Each voice download is independent — users only download the voices they want to use.
 */
data class PiperVoice(
    /** sherpa-onnx archive id, e.g. "en_US-lessac-medium" — also the base name of the .onnx file. */
    val id: String,
    val displayName: String,
    /** Approximate download size in MB, for display in the picker. */
    val sizeMb: Int,
) {
    /** Directory name produced by extracting the archive. */
    val archiveBaseName: String get() = "vits-piper-$id"
    val downloadUrl: String get() =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archiveBaseName.tar.bz2"
}

object PiperVoices {
    val ALL = listOf(
        PiperVoice("en_US-amy-medium", "Amy (American Female)", 63),
        PiperVoice("en_US-lessac-medium", "Lessac (American Female)", 63),
        PiperVoice("en_US-ryan-medium", "Ryan (American Male)", 63),
        PiperVoice("en_US-hfc_female-medium", "HFC (American Female)", 63),
        PiperVoice("en_US-libritts_r-medium", "LibriTTS-R (American)", 110),
        PiperVoice("en_GB-alan-medium", "Alan (British Male)", 63),
        PiperVoice("en_GB-southern_english_female-medium", "Southern English (Female)", 63),
        PiperVoice("en_GB-northern_english_male-medium", "Northern English (Male)", 63),
    )

    const val DEFAULT_VOICE_ID = "en_US-lessac-medium"

    fun byId(id: String): PiperVoice? = ALL.firstOrNull { it.id == id }
}
