# EpubVoice handoff

Read this first in a new session. Repo: https://github.com/Sigly/EpubVoice (branch `main`).

## What the app is
Native Android app (Kotlin, Jetpack Compose). Reads an EPUB aloud with any TTS engine, with custom colors and per-character dialogue colors. Every push to `main` builds an APK with GitHub Actions and publishes it as a plain `.apk` in Releases (`build-N`). No Android SDK in the sandbox, so CI is the compile check: push, then `gh api repos/Sigly/EpubVoice/actions/runs`.

User: Ethan. Reading the web novel "Overgeared" (about 1000+ chapters). Does not want to pay for any API. Prefers no em dashes in writing.

## Speaker attribution: three layers
Priority is manual fix, then AI labels, then offline detector.

1. **Offline detector**: `app/src/main/java/com/ethan/epubvoice/SpeakerDetector.kt`. Finds characters from the whole book, then scores candidate speakers per quote with a small learned model (29 features, weights in `W`). Quote order is processed sequentially so turn taking feeds forward. Scare quotes and `[system]` paragraphs get `Segment.scare = true`. Low confidence calls are `guess = true` (italic).
2. **AI labels from chat**: user taps Characters > Export for Claude, sends the text file here, Claude writes `epubvoice-ai.txt`, user taps Restore backup. Keys are `chapter:paragraph:segmentIndex`.
3. **Manual**: long-press a line in the reader.

## IMPORTANT constraints
- **Quote numbering must not change.** The export lists `Q1, Q2...` as the quoted segments (`isQuote == true`) in order, and Restore maps them back the same way. Every regex quote match must stay a quote segment. Scare quotes stay `isQuote = true` with `scare = true`. Changing the segmentation breaks all saved AI labels.
- Short reply format (what the app imports):
  ```
  EPUBVOICE-AI 1
  #719            <- chapter index (0-based number shown after "CHAPTER" in the export)
  12: Agnus
  13: Bob | Bob | -
  ```
  One name per Q separated by `|`. `-` means not character speech (scare quote, title, system text). `System` for game windows. List `#N` for every chapter analyzed, even with no quotes.
- Use one spelling per character (Grid, Lauel, Kraugel, Pagma, Madra, Agnus, Faker, Oasis...). Generic groups used so far: Soldiers, Crowd, Player, Vampire, Red Knight, Maid.

## Status of labeling
- Chapters **709 to 728 are labeled** (about 650 quotes): `data/labels-709-728.txt`. The user already restored this file in the app.
- The user's export covered chapters 709 to 808 (file `claude-chapters-710-809.txt`, shown in the app as chapters 710 to 809). Chapters 729 to 808 are not labeled yet.
- Estimated pace: 20 to 30 chapters per sitting by reading everything. Reading is expensive (about 2,500 tokens per chapter). Cheaper plan: run the offline detector first, then read only low-confidence quotes.
- The user may attach the whole EPUB to the new session. Parse it with the same logic as `EpubParser.kt` (zip, container.xml, OPF spine, XHTML, leaf blocks p/h1-h6/li/blockquote) so paragraph indexes match the app.

## Retraining the detector
Prototype lives in `tools/detector/` (Python 3, needs `regex`, `numpy`, `scipy`).
- `load.py` parses the export file (edit `SRC` to point at it) and the label file.
- `det.py` person lexicon and scare quote rule. `feat.py` candidates and features. `train.py` builds samples with teacher forced history, `fit.py` fits a softmax over candidates with L2 and does 5-fold CV by chapter, `infer.py` does sequential evaluation.
- Held out result with 20 chapters: about 65% sequential accuracy on named speakers (always guessing Grid is about 36%). Confidence over 0.8 is right about 82% of the time.
- Strict speech verb features (index 27, 28) are fixed at +4 because this book almost never uses "said Name", so they cannot be learned from it.
- After retraining, copy the new weights into `W` in `SpeakerDetector.kt`. The Kotlin `features()` must stay identical to `feat.py` (feature order matters). The Kotlin version has not been run against the Python version on the same text, only compiled, so a parity check would be a good first job.
- Known gaps: unreachable speakers (about 6%) when the name is not in the lexicon (Iyarugt, Noe, Ruson, "Oppa!" meaning Ruby), group speakers, pronoun-only dialogue in fights.

## Ideas not done
- Pre-fill with the detector, export only low confidence quotes.
- "Set this speaker for the rest of the scene" action in the reader.
- Default Export count smaller than 100.
