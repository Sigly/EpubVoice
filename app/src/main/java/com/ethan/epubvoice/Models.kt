package com.ethan.epubvoice

data class Chapter(val title: String, val paragraphs: List<String>)

data class Book(val title: String, val chapters: List<Chapter>)

/** One book in the library. [cover] is a file path or empty. */
data class LibraryEntry(
    val id: String,
    val title: String,
    val cover: String,
    val chapter: Int,
    val paragraph: Int,
    val total: Int,
    val added: Long
)

data class Bookmark(val chapter: Int, val paragraph: Int, val preview: String, val time: Long)

/** A piece of a paragraph. Quoted pieces are dialogue and may have a speaker. */
data class Segment(
    val text: String,
    val isQuote: Boolean,
    val speaker: String? = null,
    /** true when the speaker is a guess rather than stated in the text */
    val guess: Boolean = false,
    /** true for quoted terms, titles and system text that are not speech */
    val scare: Boolean = false
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
    val background: Int = 0xFFEBEBEB.toInt(),
    val narration: Int = 0xFF1C1C1C.toInt(),
    val dialogue: Int = 0xFF1F5FC8.toInt(),
    val highlight: Int = 0xFFC9DCFF.toInt(),
    val chapterTitle: Int = 0xFF1F5FC8.toInt(),
    val fontSize: Float = 18f,
    /** "system:<package>" (empty package = device default) or "http:<providerId>" */
    val engineId: String = "system:",
    val voice: String = "",
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val autoColors: Boolean = true,
    /** color a character's dialogue by the hair or eye color the text gives them */
    val lookColors: Boolean = true,
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
    /** chapters analyzed at the same time, and seconds to wait between batches (free tiers need 1 and a few seconds) */
    val aiParallel: Int = 1,
    val aiDelay: Int = 4,
    val lastUri: String = "",
    val lastBookId: String = "",
    val lastChapter: Int = 0,
    val lastParagraph: Int = 0
)
