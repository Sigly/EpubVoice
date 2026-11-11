# EpubVoice

An Android EPUB reader that reads aloud, with any text-to-speech voice you can add, full control over text colors, and automatic coloring of each character's dialogue.

## Get the APK (no Android Studio needed)

1. Create a new empty repository on GitHub.
2. Upload everything in this folder to it (or `git push` it). Keep the `.github` folder.
3. Open the repository's **Actions** tab, pick **Build APK**, and press **Run workflow**. It also runs on every push.
4. When it finishes (about 5 minutes), open the run and download the **EpubVoice-debug-apk** artifact. Unzip it and copy `app-debug.apk` to your phone.
5. Open the APK on the phone and allow installs from your file manager or browser when asked.

To build locally instead: install Android Studio or the Android SDK plus JDK 17, then run `gradle assembleDebug` (Gradle 8.7 or newer). The APK lands in `app/build/outputs/apk/debug/`.

## Using it

- **Open** picks an `.epub` file. The app remembers your place per book.
- **Play** reads from the highlighted paragraph. Tap any paragraph to jump there. `<` and `>` move one paragraph, the double arrows move one chapter.
- Audio keeps playing with the screen off.

### Adding text-to-speech voices

- **Installed engines.** Install any Android TTS engine (Google, Samsung, RHVoice, Sherpa-ONNX/Piper apps, and so on). It appears under Settings > Voice engine automatically, along with its voice list.
- **Custom services.** Settings > Custom TTS services lets you add any HTTP service that returns audio. `{{text}}` and `{{voice}}` are filled in for the URL and the body. Presets are included for OpenAI-style APIs and for a simple GET server such as Piper, Kokoro or XTTS running on your network. Add your API key in the headers box (for example `Authorization: Bearer sk-...`).

### Colors

- Settings has colors for background, narration, unknown dialogue, chapter titles and the current-paragraph highlight. Every picker has RGB sliders and a hex field.
- The Characters screen lists every speaker the app found, most lines first. Tap a swatch to set that character's color. By default each character also gets an automatic color so you see the effect right away. Switch that off if you only want colors you choose.

### How speaker detection works

The app scans each chapter for quoted dialogue (straight or curly double quotes) and looks nearby for patterns like `said Harry`, `Harry said` or `Harry: "..."`. Quotes with no name nearby take the speaker from the same sentence, or alternate in a back-and-forth exchange. If a character is known by two names, use **Merge** on the Characters screen. If a line is wrong, long-press it in the reader and pick the right speaker, mark it as not dialogue, or set it back to auto.

It also understands single curly quotes used for thoughts, treats action beats (`"Fine." Harry shrugged.`) as likely speakers, and for lines with no name nearby it picks the character mentioned most in the surrounding paragraphs. Those weaker calls are shown in italics so you can tell them from stated attributions.

### AI attribution

Settings > AI dialogue attribution takes an API key for Anthropic or any OpenAI-compatible service. Then, on the Characters screen, **Analyze chapter** (or **Next 10 chapters**) sends the chapter to the model, which decides who speaks or thinks every quoted line using the whole context. Results are saved per book on the phone, so each chapter is only paid for once. You can also turn on automatic analysis so the current and next chapters are analyzed as you read.

**Free option:** Google's Gemini API has a free tier. In Settings, press "Use Google Gemini (free tier)" and paste a key from aistudio.google.com. Keep "At once" at 1 and the delay at a few seconds so you stay under the free rate limits. Free-tier content may be used by Google to improve its products.

**Whole book:** the Characters screen has a "Whole book" button that runs in the background with a notification, saves after every batch, and resumes where it stopped if you hit a limit or press Stop. "Back up" and "Restore backup" save and load the AI results, your manual fixes, aliases and colors as a JSON file. Backups match the app version that made them, so restore with the same version when possible.

**Use Claude chat instead of an API:** on the Characters screen, **Export for Claude** saves a text file of the chapters you pick (40 by default). Attach it in a chat with Claude and ask it to analyze the dialogue. It sends back `epubvoice-ai.txt`, and **Restore backup** applies it. No API key or per-use charge, just your normal Claude usage.

Builds are signed with a fixed debug key (`app/debug.keystore`), so each new APK installs over the old one. This key is public in the repo, which is fine for a personal sideloaded app.

Priority when sources disagree: your manual fix, then the AI, then the offline rules. The chapter text is sent to the provider you choose, and your key is stored only on the phone.

## Project layout

- `EpubParser.kt` reads the EPUB (zip, OPF spine, XHTML) into chapters and paragraphs.
- `SpeakerDetector.kt` finds dialogue and attributes it to speakers.
- `SpeechEngines.kt` has the system TTS engine and the HTTP TTS engine behind one interface.
- `ReaderViewModel.kt` holds state, playback loop, and settings.
- `Ui.kt` and `MainActivity.kt` are the Compose screens.
