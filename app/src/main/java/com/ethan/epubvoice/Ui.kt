package com.ethan.epubvoice

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@Composable
fun AssignDialog(vm: ReaderViewModel, ch: Int, p: Int, seg: Int, onDismiss: () -> Unit) {
    val current = vm.effectiveSegments(ch, p).getOrNull(seg)
    var name by remember { mutableStateOf(current?.speaker ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who is speaking?") },
        text = {
            Column {
                Text(current?.text?.take(140) ?: "", maxLines = 4, fontSize = 13.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Character name") }
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    vm.characters.take(15).forEach { (n, _) -> TextButton({ name = n }) { Text(n) } }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.setOverride(ch, p, seg, name.trim()); onDismiss() },
                enabled = name.isNotBlank()
            ) { Text("Set") }
        },
        dismissButton = {
            Row {
                TextButton({ vm.setOverride(ch, p, seg, ""); onDismiss() }) { Text("Not dialogue") }
                TextButton({ vm.setOverride(ch, p, seg, null); onDismiss() }) { Text("Auto") }
            }
        }
    )
}

// ---------------------------------------------------------------- characters

@Composable
fun CharactersScreen(vm: ReaderViewModel, back: () -> Unit) {
    val s = vm.settings
    var pick by remember { mutableStateOf<String?>(null) } // "narration", "dialogue" or a character name
    var merging by remember { mutableStateOf<String?>(null) }
    var showWhole by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }
    var showExport by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val claudeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val pe = pendingExport
        if (uri != null && pe != null) vm.exportForClaude(uri, pe.first, pe.second)
        pendingExport = null
    }

    if (showExport) {
        var first by remember { mutableStateOf((vm.chapterIndex + 1).toString()) }
        var count by remember { mutableStateOf("40") }
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export chapters for Claude") },
            text = {
                Column {
                    Text(
                        "Saves a text file of these chapters. Attach it in a chat with Claude and ask it to analyze the dialogue, then use Restore backup on the file it sends back.",
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = first,
                        onValueChange = { first = it },
                        label = { Text("First chapter") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = count,
                        onValueChange = { count = it },
                        label = { Text("How many chapters") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val f = ((first.toIntOrNull() ?: 1).coerceAtLeast(1)) - 1
                    val c = (count.toIntOrNull() ?: 40).coerceIn(1, 200)
                    pendingExport = f to c
                    showExport = false
                    claudeLauncher.launch("claude-chapters-${f + 1}-${f + c}.txt")
                }) { Text("Save file") }
            },
            dismissButton = { TextButton({ showExport = false }) { Text("Cancel") } }
        )
    }

    if (showWhole) {
        val remaining = vm.aiRemaining()
        AlertDialog(
            onDismissRequest = { showWhole = false },
            title = { Text("Analyze the whole book?") },
            text = {
                Text(
                    "$remaining chapters still need analysis. " +
                        (if (s.aiKind == "anthropic")
                            "Anthropic bills per use, roughly a tenth of a cent per chapter (an estimate, about $${"%.2f".format(remaining * 0.001)} total). "
                        else
                            "Free tiers have daily limits and may stop partway. ") +
                        "It runs in the background with a notification, saves as it goes, and you can stop and resume any time. " +
                        "The pause between requests is set in Settings."
                )
            },
            confirmButton = { TextButton({ showWhole = false; vm.analyzeWholeBook() }) { Text("Start") } },
            dismissButton = { TextButton({ showWhole = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(back) { Text("‹ Back", color = Blue) }
            Text("Text colors", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                Text("AI dialogue attribution", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                if (!vm.aiReady()) {
                    Text(
                        "Add an API key under Settings > AI to let a language model work out who speaks each line, including thoughts and lines with no name nearby.",
                        fontSize = 12.sp
                    )
                } else {
                    val done = vm.aiChapterDone(vm.chapterIndex)
                    Text(
                        if (done) "This chapter has been analyzed by AI." else "This chapter has not been analyzed by AI yet.",
                        fontSize = 12.sp
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Button({ vm.analyzeRange(vm.chapterIndex, vm.chapterIndex, force = done) }, enabled = !vm.aiBusy) {
                            Text(if (done) "Re-analyze chapter" else "Analyze chapter")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton({ vm.analyzeRange(vm.chapterIndex, vm.chapterIndex + 9) }, enabled = !vm.aiBusy) {
                            Text("Next 10 chapters")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton({ showWhole = true }, enabled = !vm.aiBusy) { Text("Whole book") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton({ vm.cancelAi() }, enabled = vm.aiBusy) { Text("Stop") }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton({ showExport = true }, enabled = vm.book != null) { Text("Export for Claude") }
                    TextButton({ exportLauncher.launch("epubvoice-backup.json") }) { Text("Back up") }
                    TextButton({ importLauncher.launch(arrayOf("*/*")) }) { Text("Restore backup") }
                }
                if (vm.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                vm.aiStatus?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Text(
                    "Italic lines are guesses by the offline rules. Lines you fix by hand always win.",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                HorizontalDivider()
                ColorRow("Narration (everything outside quotes)", s.narration) { pick = "narration" }
                ColorRow("Dialogue with no known speaker", s.dialogue) { pick = "dialogue" }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-assign colors to characters", Modifier.weight(1f))
                    Switch(s.autoColors, { vm.update { copy(autoColors = it) } })
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Color by hair or eye color found in the text", Modifier.weight(1f))
                    Switch(s.lookColors, { vm.update { copy(lookColors = it) }; vm.refreshLooks() })
                }
                Row {
                    TextButton({ vm.update { copy(speakerColors = emptyMap()) } }) { Text("Reset character colors") }
                }
                Text(
                    "Detected characters, most lines first. Tap a color to change it. Long-press any quoted line in the reader to fix who said it.",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider()
            }
            items(vm.characters) { (name, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(vm.colorFor(name)) { pick = name }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color(vm.colorFor(name)), fontWeight = FontWeight.Bold)
                        Text("$count lines" + (vm.looks[name]?.let { "  \u00b7  ${it.label}" } ?: ""), fontSize = 12.sp)
                    }
                    TextButton({ merging = name }) { Text("Merge") }
                    if (s.speakerColors.containsKey(name)) {
                        TextButton({ vm.setSpeakerColor(name, null) }) { Text("Reset") }
                    }
                }
            }
        }
    }

    pick?.let { key ->
        val cur = when (key) {
            "narration" -> s.narration
            "dialogue" -> s.dialogue
            else -> vm.colorFor(key)
        }
        ColorPickerDialog(key.replaceFirstChar { it.uppercase() }, cur, { pick = null }) { c ->
            when (key) {
                "narration", "dialogue" -> vm.setColor(key, c)
                else -> vm.setSpeakerColor(key, c)
            }
            pick = null
        }
    }

    merging?.let { name ->
        var target by remember(name) { mutableStateOf(s.aliases[name] ?: "") }
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Merge \"$name\"") },
            text = {
                Column {
                    Text("Treat every line by $name as spoken by another character. Leave empty to undo.", fontSize = 13.sp)
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        singleLine = true,
                        label = { Text("Merge into") }
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        vm.characters.filter { it.first != name }.take(15).forEach { (n, _) ->
                            TextButton({ target = n }) { Text(n) }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ vm.setAlias(name, target); merging = null }) { Text("Apply") } },
            dismissButton = { TextButton({ merging = null }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(vm: ReaderViewModel, back: () -> Unit) {
    val s = vm.settings
    var pick by remember { mutableStateOf<String?>(null) }
    var showVoices by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HttpProvider?>(null) }
    var fontSize by remember(s.fontSize) { mutableStateOf(s.fontSize) }
    var rate by remember(s.rate) { mutableStateOf(s.rate) }
    var pitch by remember(s.pitch) { mutableStateOf(s.pitch) }

    LaunchedEffect(s.engineId, s.providers) { vm.refreshVoices() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(back) { Text("‹ Back", color = Blue) }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Header("Colors")
        ColorRow("Background", s.background) { pick = "background" }
        ColorRow("Narration text", s.narration) { pick = "narration" }
        ColorRow("Dialogue text (unknown speaker)", s.dialogue) { pick = "dialogue" }
        ColorRow("Chapter titles", s.chapterTitle) { pick = "title" }
        ColorRow("Current paragraph highlight", s.highlight) { pick = "highlight" }
        Text("Per-character colors are on the Characters screen.", fontSize = 12.sp)

        Header("Text size: ${fontSize.toInt()}")
        Slider(fontSize, { fontSize = it }, valueRange = 12f..34f, onValueChangeFinished = { vm.update { copy(fontSize = fontSize) } })

        Header("Voice engine")
        vm.engineOptions().forEach { opt ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.selectEngine(opt.id) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(s.engineId == opt.id, { vm.selectEngine(opt.id) })
                Text(opt.label)
            }
        }
        if (!s.engineId.startsWith("http:")) {
            OutlinedButton({ showVoices = true }, enabled = vm.voices.isNotEmpty()) {
                Text("Voice: " + s.voice.ifEmpty { "default" })
            }
        }
        Header("Speed: ${"%.2f".format(rate)}")
        Slider(rate, { rate = it }, valueRange = 0.5f..2.5f, onValueChangeFinished = { vm.update { copy(rate = rate) } })
        if (!s.engineId.startsWith("http:")) {
            Header("Pitch: ${"%.2f".format(pitch)}")
            Slider(pitch, { pitch = it }, valueRange = 0.5f..2f, onValueChangeFinished = { vm.update { copy(pitch = pitch) } })
        }
        Row {
            Button({ vm.testVoice() }) { Text("Test voice") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ vm.pause() }) { Text("Stop") }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

        Header("Custom TTS services")
        Text(
            "Add any service that returns audio: OpenAI-compatible APIs, ElevenLabs, or a Piper/Kokoro/XTTS server on your network. " +
                "Other on-device engines show up above automatically once installed from the Play Store.",
            fontSize = 12.sp
        )
        s.providers.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f))
                TextButton({ editing = p }) { Text("Edit") }
                TextButton({ vm.deleteProvider(p.id) }) { Text("Delete") }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            OutlinedButton({
                editing = HttpProvider(
                    id = UUID.randomUUID().toString(),
                    name = "OpenAI-compatible",
                    url = "https://api.openai.com/v1/audio/speech",
                    method = "POST",
                    headers = "Authorization: Bearer YOUR_API_KEY",
                    bodyTemplate = "{\"model\":\"tts-1\",\"input\":\"{{text}}\",\"voice\":\"{{voice}}\",\"response_format\":\"mp3\"}",
                    voice = "alloy"
                )
            }) { Text("Add OpenAI-style") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({
                editing = HttpProvider(
                    id = UUID.randomUUID().toString(),
                    name = "My server",
                    url = "http://192.168.1.50:5000/tts?text={{text}}&voice={{voice}}",
                    method = "GET",
                    voice = "default"
                )
            }) { Text("Add GET server") }
        }
        Header("AI dialogue attribution")
        Text(
            "A language model reads each chapter and decides who speaks or thinks every quoted line. " +
                "The chapter text is sent to the provider you choose, and your provider bills you per use (usually a few cents per book with a small model). " +
                "Your key is stored only on this phone.",
            fontSize = 12.sp
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            OutlinedButton({
                vm.update {
                    copy(
                        aiKind = "openai",
                        aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
                        aiModel = "gemini-2.5-flash",
                        aiParallel = 1,
                        aiDelay = 5
                    )
                }
            }) { Text("Use Google Gemini (free tier)") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({
                vm.update { copy(aiKind = "anthropic", aiBaseUrl = "", aiModel = "claude-haiku-5-5", aiParallel = 2, aiDelay = 1) }
            }) { Text("Use Anthropic") }
        }
        Text(
            "Gemini: get a free key at aistudio.google.com, paste it below. Free-tier content may be used by Google to improve its products.",
            fontSize = 12.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(s.aiKind == "anthropic", {
                vm.update { copy(aiKind = "anthropic", aiModel = if (aiKind != "anthropic") "claude-haiku-5-5" else aiModel) }
            })
            Text("Anthropic")
            Spacer(Modifier.width(12.dp))
            RadioButton(s.aiKind == "openai", {
                vm.update { copy(aiKind = "openai", aiModel = if (aiKind != "openai") "gpt-4o-mini" else aiModel) }
            })
            Text("OpenAI-compatible")
        }
        OutlinedTextField(
            value = s.aiKey,
            onValueChange = { v -> vm.update { copy(aiKey = v.trim()) } },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.aiModel,
            onValueChange = { v -> vm.update { copy(aiModel = v.trim()) } },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.aiBaseUrl,
            onValueChange = { v -> vm.update { copy(aiBaseUrl = v.trim()) } },
            label = { Text("Base URL (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = s.aiParallel.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { n -> vm.update { copy(aiParallel = n.coerceIn(1, 8)) } } },
                label = { Text("At once") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = s.aiDelay.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { n -> vm.update { copy(aiDelay = n.coerceIn(0, 120)) } } },
                label = { Text("Seconds between batches") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Analyze chapters automatically as I read", Modifier.weight(1f))
            Switch(s.aiAuto, { v -> vm.update { copy(aiAuto = v) } })
        }
        Spacer(Modifier.size(32.dp))
    }

    pick?.let { key ->
        val (label, cur) = when (key) {
            "background" -> "Background" to s.background
            "narration" -> "Narration text" to s.narration
            "dialogue" -> "Dialogue text" to s.dialogue
            "title" -> "Chapter titles" to s.chapterTitle
            else -> "Highlight" to s.highlight
        }
        ColorPickerDialog(label, cur, { pick = null }) { vm.setColor(key, it); pick = null }
    }

    if (showVoices) {
        AlertDialog(
            onDismissRequest = { showVoices = false },
            title = { Text("Voice") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item { TextButton({ vm.update { copy(voice = "") }; showVoices = false }) { Text("Default") } }
                    items(vm.voices) { v ->
                        TextButton({ vm.update { copy(voice = v) }; showVoices = false }) { Text(v) }
                    }
                }
            },
            confirmButton = {}
        )
    }

    editing?.let { p ->
        ProviderDialog(p, onSave = { vm.saveProvider(it); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun Header(t: String) {
    Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
fun Swatch(color: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(color))
            .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    )
}

@Composable
fun ColorRow(label: String, color: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Swatch(color, onClick)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
fun ProviderDialog(initial: HttpProvider, onSave: (HttpProvider) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var method by remember { mutableStateOf(initial.method) }
    var headers by remember { mutableStateOf(initial.headers) }
    var body by remember { mutableStateOf(initial.bodyTemplate) }
    var voice by remember { mutableStateOf(initial.voice) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom TTS service") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Use {{text}} and {{voice}} in the URL or body. The service must reply with audio (mp3, wav or ogg).",
                    fontSize = 12.sp
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("URL") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("POST", "GET").forEach { m ->
                        RadioButton(method == m, { method = m })
                        Text(m)
                        Spacer(Modifier.width(12.dp))
                    }
                }
                OutlinedTextField(headers, { headers = it }, label = { Text("Headers, one per line (Key: Value)") })
                OutlinedTextField(body, { body = it }, label = { Text("Body template (POST only)") })
                OutlinedTextField(voice, { voice = it }, label = { Text("Voice") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({
                onSave(
                    initial.copy(
                        name = name.ifBlank { "Custom" },
                        url = url.trim(),
                        method = method,
                        headers = headers,
                        bodyTemplate = body,
                        voice = voice.trim()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ColorPickerDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var color by remember { mutableIntStateOf(initial or (0xFF shl 24)) }
    var hex by remember { mutableStateOf("%06X".format(color and 0xFFFFFF)) }

    fun set(c: Int) {
        color = c or (0xFF shl 24)
        hex = "%06X".format(color and 0xFFFFFF)
    }

    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().size(44.dp).background(Color(color)).border(1.dp, Color.Gray))
                Text("Red $r", fontSize = 12.sp)
                Slider(r.toFloat(), { set((it.toInt() shl 16) or (g shl 8) or b) }, valueRange = 0f..255f)
                Text("Green $g", fontSize = 12.sp)
                Slider(g.toFloat(), { set((r shl 16) or (it.toInt() shl 8) or b) }, valueRange = 0f..255f)
                Text("Blue $b", fontSize = 12.sp)
                Slider(b.toFloat(), { set((r shl 16) or (g shl 8) or it.toInt()) }, valueRange = 0f..255f)
                OutlinedTextField(
                    value = hex,
                    onValueChange = { v ->
                        val t = v.trim().removePrefix("#").take(6).uppercase()
                        hex = t
                        if (t.length == 6) t.toIntOrNull(16)?.let { color = it or (0xFF shl 24) }
                    },
                    label = { Text("Hex (RRGGBB)") },
                    singleLine = true
                )
            }
        },
        confirmButton = { TextButton({ onPick(color) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
