package com.ethan.epubvoice

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Screen { READER, SETTINGS, CHARACTERS, CHAPTERS, LIBRARY }

@Composable
fun App(vm: ReaderViewModel) {
    var screen by remember {
        mutableStateOf(if (vm.settings.lastBookId.isEmpty() && vm.settings.lastUri.isEmpty()) Screen.LIBRARY else Screen.READER)
    }
    LaunchedEffect(vm.openSignal) {
        if (vm.openSignal > 0) screen = Screen.READER
    }
    BackHandler(enabled = screen != Screen.READER && !(screen == Screen.LIBRARY && vm.book == null)) {
        screen = Screen.READER
    }

    val bg = Color(vm.settings.background)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bg,
        contentColor = if (bg.luminance() < 0.5f) Color.White else Color.Black
    ) {
        when (screen) {
            Screen.READER -> ReaderScreen(vm, nav = { screen = it })
            Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.READER }
            Screen.CHARACTERS -> CharactersScreen(vm) { screen = Screen.READER }
            Screen.CHAPTERS -> ChaptersScreen(vm) { screen = Screen.READER }
            Screen.LIBRARY -> LibraryScreen(
                vm,
                canClose = vm.book != null,
                close = { screen = Screen.READER },
                nav = { screen = it }
            )
        }
    }
}

// ---------------------------------------------------------------- reader

@Composable
fun ReaderScreen(vm: ReaderViewModel, nav: (Screen) -> Unit) {
    val s = vm.settings
    val book = vm.book
    val listState = rememberLazyListState()
    var assign by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var showJump by remember { mutableStateOf(false) }

    LaunchedEffect(vm.chapterIndex, vm.paragraphIndex, book) {
        if (book != null) listState.animateScrollToItem(vm.paragraphIndex + 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton({ nav(Screen.LIBRARY) }, Modifier.size(42.dp)) {
                Icon(Icons.Filled.MenuBook, "Library", tint = Blue)
            }
            Column(
                Modifier.weight(1f).clickable(enabled = book != null) { nav(Screen.CHAPTERS) }.padding(horizontal = 6.dp)
            ) {
                Text(
                    book?.title ?: "EpubVoice",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book != null) {
                    Text(
                        "Chapter ${vm.chapterIndex + 1} of ${book.chapters.size}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            val marked = book != null && vm.isBookmarked(vm.chapterIndex, vm.paragraphIndex)
            IconButton({ vm.toggleBookmark(vm.chapterIndex, vm.paragraphIndex) }, Modifier.size(42.dp), enabled = book != null) {
                Icon(if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark", tint = Blue)
            }
            IconButton({ nav(Screen.CHAPTERS) }, Modifier.size(42.dp), enabled = book != null) {
                Icon(Icons.Filled.FormatListNumbered, "Chapters", tint = Blue)
            }
            IconButton({ nav(Screen.CHARACTERS) }, Modifier.size(42.dp)) {
                Icon(Icons.Filled.People, "Characters", tint = Blue)
            }
            IconButton({ nav(Screen.SETTINGS) }, Modifier.size(42.dp)) {
                Icon(Icons.Filled.Settings, "Settings", tint = Blue)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (book == null) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No book open.")
                    Spacer(Modifier.size(12.dp))
                    Button({ nav(Screen.LIBRARY) }) { Text("Open the library") }
                }
            } else {
                val chapter = book.chapters[vm.chapterIndex]
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            chapter.title,
                            color = Color(s.chapterTitle),
                            fontSize = (s.fontSize + 4f).sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    itemsIndexed(chapter.paragraphs) { p, _ ->
                        ParagraphView(
                            vm = vm,
                            ch = vm.chapterIndex,
                            p = p,
                            current = p == vm.paragraphIndex,
                            bookmarked = vm.isBookmarked(vm.chapterIndex, p),
                            onTap = { vm.jumpTo(vm.chapterIndex, p) },
                            onLongQuote = { seg -> assign = Triple(vm.chapterIndex, p, seg) }
                        )
                    }
                    item { Spacer(Modifier.size(80.dp)) }
                }
            }
        }

        if (book != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Chapter ${vm.chapterIndex + 1} / ${book.chapters.size}  (tap to jump)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showJump = true }.padding(4.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Paragraph ${vm.paragraphIndex + 1}/${book.chapters[vm.chapterIndex].paragraphs.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val total = book.chapters.size
                    var sliderValue by remember(vm.chapterIndex, total) { mutableFloatStateOf(vm.chapterIndex.toFloat()) }
                    if (total > 1) {
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = { vm.jumpTo(sliderValue.roundToInt(), 0) },
                            valueRange = 0f..(total - 1).toFloat()
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton({ vm.stepChapter(-1) }) { Icon(Icons.Filled.SkipPrevious, "Previous chapter") }
                        IconButton({ vm.step(-1) }) { Icon(Icons.Filled.NavigateBefore, "Previous paragraph", Modifier.size(32.dp)) }
                        Box(
                            Modifier.size(60.dp).clip(CircleShape).background(Blue).clickable { vm.togglePlay() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (vm.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (vm.playing) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        IconButton({ vm.step(1) }) { Icon(Icons.Filled.NavigateNext, "Next paragraph", Modifier.size(32.dp)) }
                        IconButton({ vm.stepChapter(1) }) { Icon(Icons.Filled.SkipNext, "Next chapter") }
                    }
                }
            }
        }
    }

    if (showJump) JumpDialog(vm) { showJump = false }
    assign?.let { (c, p, seg) ->
        AssignDialog(vm, c, p, seg) { assign = null }
    }
}

@Composable
fun JumpDialog(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val total = vm.book?.chapters?.size ?: 0
    var text by remember { mutableStateOf("") }
    val n = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to chapter") },
        text = {
            Column {
                Text(
                    "Type a chapter number from 1 to $total. This is the number the app shows, which can differ from the number in the chapter title.",
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() } },
                    singleLine = true,
                    label = { Text("Chapter number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (n != null) {
                        vm.jumpTo(n - 1, 0)
                        onDismiss()
                    }
                },
                enabled = n != null && n in 1..total
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ParagraphView(
    vm: ReaderViewModel,
    ch: Int,
    p: Int,
    current: Boolean,
    bookmarked: Boolean,
    onTap: () -> Unit,
    onLongQuote: (Int) -> Unit
) {
    val s = vm.settings
    val segs = remember(vm.book, s.overrides, s.aliases, ch, p, vm.dataVersion) { vm.effectiveSegments(ch, p) }
    val bounds = remember(segs) {
        var acc = 0
        segs.map { acc += it.text.length; acc }
    }
    val text = remember(segs, s.narration, s.dialogue, s.speakerColors, s.autoColors, vm.dataVersion) {
        buildAnnotatedString {
            for (seg in segs) {
                val c = if (seg.isQuote && !seg.scare) vm.colorFor(seg.speaker) else s.narration
                val style = if (seg.isQuote && seg.guess && !seg.scare) {
                    SpanStyle(color = Color(c), fontStyle = FontStyle.Italic)
                } else {
                    SpanStyle(color = Color(c))
                }
                withStyle(style) { append(seg.text) }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = text,
        fontSize = s.fontSize.sp,
        lineHeight = (s.fontSize * 1.45f).sp,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .background(if (current) Color(s.highlight) else Color.Transparent)
            .drawBehind {
                if (bookmarked) drawRect(Blue, size = Size(7f, size.height))
            }
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .pointerInput(segs) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { pos ->
                        val l = layout
                        if (l != null) {
                            val off = l.getOffsetForPosition(pos)
                            val idx = bounds.indexOfFirst { off < it }.let { if (it < 0) bounds.lastIndex else it }
                            if (idx >= 0 && segs[idx].isQuote) onLongQuote(idx)
                        }
                    }
                )
            }
    )
}

// ---------------------------------------------------------------- chapters and bookmarks

@Composable
fun ChaptersScreen(vm: ReaderViewModel, back: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val chapters = vm.book?.chapters ?: emptyList()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (vm.chapterIndex - 2).coerceAtLeast(0))
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.Filled.ArrowBack, "Back", tint = Blue) }
            Text("Contents", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = Blue) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chapters") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Bookmarks (${vm.bookmarks.size})") })
        }

        if (tab == 0) {
            val q = query.trim()
            val num = q.toIntOrNull()
            val clear: (@Composable () -> Unit)? = if (query.isNotEmpty()) {
                { IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") } }
            } else {
                null
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search by number or title") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = clear,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ scope.launch { listState.animateScrollToItem(0) } }) { Text("Start") }
                OutlinedButton({ scope.launch { listState.animateScrollToItem((vm.chapterIndex - 2).coerceAtLeast(0)) } }) {
                    Text("Current")
                }
                OutlinedButton({ scope.launch { listState.animateScrollToItem((chapters.size - 1).coerceAtLeast(0)) } }) {
                    Text("End")
                }
            }
            val shown = remember(q, chapters) {
                chapters.withIndex().filter { (i, c) ->
                    q.isEmpty() || c.title.contains(q, ignoreCase = true) || (i + 1).toString().startsWith(q)
                }.toList()
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                if (num != null && num in 1..chapters.size) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Blue)
                                .clickable { vm.jumpTo(num - 1, 0); back() }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Go to chapter $num", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.NavigateNext, null, tint = Color.White)
                        }
                    }
                }
                items(shown, key = { it.index }) { iv ->
                    val i = iv.index
                    val c = iv.value
                    val isCurrent = i == vm.chapterIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { vm.jumpTo(i, 0); back() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${i + 1}",
                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(56.dp)
                        )
                        Text(
                            c.title,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (vm.bookmarks.any { it.chapter == i }) {
                            Icon(Icons.Filled.Bookmark, null, tint = Blue, modifier = Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        } else {
            val here = vm.isBookmarked(vm.chapterIndex, vm.paragraphIndex)
            Button(
                { vm.toggleBookmark(vm.chapterIndex, vm.paragraphIndex) },
                Modifier.fillMaxWidth().padding(12.dp),
                enabled = vm.book != null
            ) {
                Icon(if (here) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null)
                Spacer(Modifier.width(8.dp))
                Text(if (here) "Remove bookmark at this spot" else "Bookmark this spot (chapter ${vm.chapterIndex + 1})")
            }
            if (vm.bookmarks.isEmpty()) {
                Text(
                    "No bookmarks yet. Tap the bookmark icon while reading to save your place.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.bookmarks, key = { "${it.chapter}:${it.paragraph}" }) { bm ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.jumpTo(bm.chapter, bm.paragraph); back() }
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Bookmark, null, tint = Blue)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                "Chapter ${bm.chapter + 1}, paragraph ${bm.paragraph + 1}",
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(bm.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        }
                        IconButton({ vm.removeBookmark(bm) }) { Icon(Icons.Filled.Delete, "Delete bookmark") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- library

@Composable
fun rememberCover(path: String): ImageBitmap? = remember(path) {
    if (path.isEmpty()) {
        null
    } else {
        try {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
}

@Composable
fun LibraryScreen(vm: ReaderViewModel, canClose: Boolean, close: () -> Unit, nav: (Screen) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf<LibraryEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<LibraryEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addBooks(uris)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canClose) {
                IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back", tint = Blue) }
            } else {
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Library", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (vm.library.size == 1) "1 book" else "${vm.library.size} books",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            IconButton({ picker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.Add, "Add books", tint = Blue) }
            IconButton({ nav(Screen.SETTINGS) }) { Icon(Icons.Filled.Settings, "Settings", tint = Blue) }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        if (vm.library.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MenuBook, null, tint = Color.White, modifier = Modifier.size(52.dp))
                }
                Spacer(Modifier.size(20.dp))
                Text("Your library is empty", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Add one or more EPUB files. Each book keeps its own place, bookmarks and character colors.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                Button({ picker.launch(arrayOf("*/*")) }) { Text("Add books") }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(vm.library, key = { it.id }) { e ->
                    BookCard(
                        e = e,
                        current = vm.book != null && e.id == vm.settings.lastBookId,
                        onClick = {
                            if (vm.book != null && e.id == vm.settings.lastBookId) close() else vm.openLibraryBook(e.id)
                        },
                        onLong = { menu = e }
                    )
                }
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.68f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, Blue, RoundedCornerShape(14.dp))
                            .clickable { picker.launch(arrayOf("*/*")) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Add, null, tint = Blue, modifier = Modifier.size(40.dp))
                            Text("Add books", color = Blue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    menu?.let { e ->
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton({ pinShortcut(ctx, e); menu = null }) {
                        Icon(Icons.Filled.Home, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add to home screen")
                    }
                    TextButton({ confirmDelete = e; menu = null }) {
                        Icon(Icons.Filled.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Remove from library")
                    }
                }
            },
            confirmButton = { TextButton({ menu = null }) { Text("Close") } }
        )
    }
    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove this book?") },
            text = {
                Text("${e.title} will be removed from the library. Its bookmarks and the character labels saved for it are deleted too.")
            },
            confirmButton = { TextButton({ vm.removeBook(e.id); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(e: LibraryEntry, current: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val img = rememberCover(e.cover)
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(if (current) 2.dp else 1.dp, if (current) Blue else MaterialTheme.colorScheme.outline, shape)
        ) {
            if (img != null) {
                Image(img, e.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.MenuBook, null, tint = Blue, modifier = Modifier.size(44.dp))
                    Text(
                        e.title,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            if (e.total > 1) {
                LinearProgressIndicator(
                    progress = { ((e.chapter + 1).toFloat() / e.total).coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = Blue,
                    trackColor = Color(0x99000000)
                )
            }
        }
        Text(
            e.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            if (e.total > 0) "Chapter ${e.chapter + 1} of ${e.total}" else "Not opened yet",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

/** Asks the launcher to pin a shortcut that opens this book, with the cover as its icon. */
fun pinShortcut(ctx: Context, e: LibraryEntry) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
        Toast.makeText(ctx, "This launcher does not support home screen shortcuts", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(ctx, MainActivity::class.java)
        .setAction(ACTION_OPEN_BOOK)
        .putExtra(EXTRA_BOOK_ID, e.id)
    val info = ShortcutInfoCompat.Builder(ctx, "book_" + e.id)
        .setShortLabel(e.title.take(24))
        .setLongLabel(e.title.take(60))
        .setIcon(IconCompat.createWithBitmap(shortcutBitmap(ctx, e)))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
}

private fun shortcutBitmap(ctx: Context, e: LibraryEntry): Bitmap {
    val size = 192
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val src = if (e.cover.isNotEmpty()) {
        try {
            BitmapFactory.decodeFile(e.cover)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }
    if (src != null) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val dst = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(dst, 40f, 40f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val side = minOf(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        canvas.drawBitmap(src, Rect(left, top, left + side, top + side), dst, paint)
    } else {
        val d = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_foreground)
        if (d != null) {
            d.setBounds(0, 0, size, size)
            d.draw(canvas)
        }
    }
    return out
}
