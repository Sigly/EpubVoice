package com.ethan.epubvoice

data class Chapter(val title: String, val paragraphs: List<String>)

data class Book(val title: String, val chapters: List<Chapter>)

/** A piece of a paragraph. Quoted pieces are dialogue and may have a speaker. */
data class Segment(
    val text: String,
    val isQuote: Boolean,
    val speaker: String? = null,
    /** true when the speaker is a guess rather than stated in the text */
    val guess: Boolean = false
)

/** A user-defined HTTP text-to-speech service (OpenAI-compatible, Piper server, ElevenLabs, etc). */
data class HttpProvider(
    val id: String,
    val name: String,
    val url: String,
    val method: String = "POST",
    val headers: String = "",
    val bodyTemplate: String = "",
    val voice: String = ""
)

data class EngineOption(val id: String, val label: String)

data class Settings(
    val background: Int = 0xFF121212.toInt(),
    val narration: Int = 0xFFD0D0D0.toInt(),
    val dialogue: Int = 0xFFFFD54F.toInt(),
    val highlight: Int = 0xFF2A3F5F.toInt(),
    val chapterTitle: Int = 0xFF90CAF9.toInt(),
    val fontSize: Float = 18f,
    /** "system:<package>" (empty package = device default) or "http:<providerId>" */
    val engineId: String = "system:",
    val voice: String = "",
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val autoColors: Boolean = true,
    val speakerColors: Map<String, Int> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    /** key "chapter:paragraph:segment" -> speaker name, or "" meaning "not dialogue" */
    val overrides: Map<String, String> = emptyMap(),
    val providers: List<HttpProvider> = emptyList(),
    /** AI dialogue attribution: "anthropic" or "openai" (any OpenAI-compatible endpoint) */
    val aiKind: String = "anthropic",
    val aiBaseUrl: String = "",
    val aiKey: String = "",
    val aiModel: String = "claude-haiku-5-5",
    val aiAuto: Boolean = false,
    val lastUri: String = "",
    val lastChapter: Int = 0,
    val lastParagraph: Int = 0
)
