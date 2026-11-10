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

Limits: it does not understand single-quote dialogue, and it works best on prose that names speakers regularly. It is a heuristic, not language understanding, so expect to correct some lines.

## Project layout

- `EpubParser.kt` reads the EPUB (zip, OPF spine, XHTML) into chapters and paragraphs.
- `SpeakerDetector.kt` finds dialogue and attributes it to speakers.
- `SpeechEngines.kt` has the system TTS engine and the HTTP TTS engine behind one interface.
- `ReaderViewModel.kt` holds state, playback loop, and settings.
- `Ui.kt` and `MainActivity.kt` are the Compose screens.
