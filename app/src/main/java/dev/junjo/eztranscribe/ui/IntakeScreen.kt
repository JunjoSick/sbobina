package dev.junjo.eztranscribe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.junjo.eztranscribe.engine.CloudProvider
import dev.junjo.eztranscribe.share.SharedClip
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The one screen. Each shared note is a card that leads with its transcript; engines, cloud setup
 * and the file's technical details are a tap away rather than in the way.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IntakeScreen(
    state: IntakeState,
    onDecode: (SharedClip) -> Unit,
    onPlay: (SharedClip) -> Unit,
    onStop: () -> Unit,
    onTranscribe: (SharedClip, EngineChoice) -> Unit,
    cloudActions: CloudActions,
    historyActions: HistoryActions,
    modifier: Modifier = Modifier,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    if (showHistory) {
        HistoryScreen(
            entries = state.history,
            actions = historyActions,
            onBack = { showHistory = false },
            modifier = modifier,
        )
        return
    }

    // A three-minute note can take a minute or more; the screen should not sleep under it.
    val view = LocalView.current
    val busy = state.probing > 0 ||
        state.decodes.values.any { it is DecodeState.Running } ||
        state.transcripts.values.any { it is TranscribeState.Running }
    DisposableEffect(view, busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        SettingsSheet(
            state = state,
            cloud = cloudActions,
            onDismiss = { showSettings = false },
        )
    }

    // Newest note first, and a newly shared note scrolls into view at the top.
    val listState = rememberLazyListState()
    val clips = state.clips.asReversed()
    LaunchedEffect(state.clips.size) {
        if (state.clips.isNotEmpty()) listState.animateScrollToItem(0)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("Sbobina") },
                subtitle = { Text("Voice notes to text") },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(AppIcons.History, contentDescription = "History")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(AppIcons.Tune, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = insets.calculateTopPadding() + 8.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.modelDownload?.let { download -> item(key = "download") { DownloadBanner(download) } }
            if (state.probing > 0) item(key = "probing") { LoadingIndicator(Modifier.fillMaxWidth()) }

            if (state.clips.isEmpty() && state.probing == 0) {
                item(key = "empty") {
                    EmptyState(
                        historyCount = state.history.size,
                        onOpenHistory = { showHistory = true },
                        // Sized to its content, not the screen: in landscape a fixed share of the
                        // height cut off the history button.
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            items(clips, key = { it.uri.toString() }) { clip ->
                val uri = clip.uri.toString()
                NoteCard(
                    clip = clip,
                    cloudProvider = state.cloudProvider,
                    onSetUpCloud = { showSettings = true },
                    versions = EngineChoice.entries.mapNotNull { engine ->
                        state.transcripts[transcriptKey(clip, engine)]?.let { engine to it }
                    }.toMap(),
                    lastRun = state.lastRun[uri],
                    decode = state.decodes[uri],
                    isPlaying = state.playing == uri,
                    onTranscribe = { engine -> onTranscribe(clip, engine) },
                    onDecode = { onDecode(clip) },
                    onPlay = { onPlay(clip) },
                    onStop = onStop,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(historyCount: Int, onOpenHistory: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(MaterialShapes.Cookie12Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Waveform,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp),
            )
        }
        Text(
            text = "Share a voice note",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            text = "In WhatsApp or Telegram, long-press a voice note, tap share and pick Sbobina. " +
                "From Signal, save the note first, then share it from Files.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "Transcribed on this phone. Nothing leaves it unless you choose the cloud.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        if (historyCount > 0) {
            FilledTonalButton(onClick = onOpenHistory, modifier = Modifier.padding(top = 24.dp)) {
                Icon(AppIcons.History, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (historyCount == 1) "1 transcript in history" else "$historyCount transcripts in history")
            }
        }
    }
}

/** A Whisper model downloading while android-sr covers, or why the download failed. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadBanner(download: ModelDownload) {
    Surface(
        color = if (download.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            val percent = download.fraction?.let { " · ${(it * 100).roundToInt()}%" } ?: ""
            Text(download.activity + percent, style = MaterialTheme.typography.labelLarge)
            if (!download.failed) {
                Text(
                    "Android's recognizer covers until it is ready.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val fraction = download.fraction
                val bar = Modifier.fillMaxWidth().padding(top = 10.dp)
                if (fraction == null) LinearWavyProgressIndicator(bar) else LinearWavyProgressIndicator(progress = { fraction }, modifier = bar)
            }
        }
    }
}

/**
 * One shared note. Shows the version from the newest run, with chips to switch when more than
 * one engine has transcribed it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoteCard(
    clip: SharedClip,
    cloudProvider: CloudProvider?,
    onSetUpCloud: () -> Unit,
    versions: Map<EngineChoice, TranscribeState>,
    lastRun: EngineChoice?,
    decode: DecodeState?,
    isPlaying: Boolean,
    onTranscribe: (EngineChoice) -> Unit,
    onDecode: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    // A new run switches the card to its version.
    var shown by rememberSaveable(clip.uri.toString(), lastRun) { mutableStateOf(lastRun ?: EngineChoice.WHISPER_Q8) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // The cloud sends other people's voices off the phone, so every note asks first. Without a
    // key there is nothing to send with, so the cloud option opens the setup instead.
    var confirmCloud by remember { mutableStateOf(false) }
    val transcribeWith = { engine: EngineChoice ->
        when {
            engine != EngineChoice.CLOUD -> onTranscribe(engine)
            cloudProvider == null -> onSetUpCloud()
            else -> confirmCloud = true
        }
    }
    val transcript = versions[shown]
    val running = versions.values.any { it is TranscribeState.Running }

    if (confirmCloud) {
        AlertDialog(
            onDismissRequest = { confirmCloud = false },
            title = { Text("Send to the cloud?") },
            text = {
                Text(
                    "This note's audio will be uploaded to ${cloudProvider?.label ?: "the cloud provider"} " +
                        "for transcription. It contains the sender's voice.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCloud = false
                    onTranscribe(EngineChoice.CLOUD)
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirmCloud = false }) { Text("Cancel") } },
        )
    }
    if (showDetails) {
        DetailsSheet(clip, decode, isPlaying, onDecode, onPlay, onStop, onDismiss = { showDetails = false })
    }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoteBadge(working = running)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(friendlyTitle(clip), style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(clip.durationMs?.let(::formatClock), clip.container?.label).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(AppIcons.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        for (engine in EngineChoice.entries) {
                            DropdownMenuItem(
                                text = { Text("Transcribe with ${engineLabel(engine, cloudProvider)}") },
                                leadingIcon = {
                                    Icon(if (engine == EngineChoice.CLOUD) AppIcons.Cloud else AppIcons.Waveform, contentDescription = null)
                                },
                                enabled = clip.readError == null && versions[engine] !is TranscribeState.Running,
                                onClick = {
                                    menuOpen = false
                                    transcribeWith(engine)
                                },
                            )
                        }
                    }
                }
            }

            clip.readError?.let { Problem("Could not read the file", it) }
            clip.extractorError?.let { Problem("Could not read the audio format", it) }

            if (versions.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (engine in versions.keys) {
                        FilterChip(
                            selected = engine == shown,
                            onClick = { shown = engine },
                            label = { Text(engineLabel(engine, cloudProvider)) },
                        )
                    }
                }
            }

            if (clip.readError == null) {
                if (versions.isEmpty()) {
                    EngineChooser(cloudProvider = cloudProvider, onChoose = transcribeWith)
                } else {
                    TranscriptBody(
                        transcript = transcript,
                        cloudProvider = cloudProvider,
                        onTranscribe = { transcribeWith(shown) },
                    )
                }
            }

            TextButton(onClick = { showDetails = true }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text("Details")
            }
        }
    }
}

/** The shape at the top of a card: a soft cookie at rest, a morphing loading indicator while working. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoteBadge(working: Boolean) {
    if (working) {
        LoadingIndicator(Modifier.size(48.dp))
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Waveform, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/**
 * The first choice for a new note: two large buttons, on this phone or in the cloud, and Android's
 * recognizer below them, smaller, for when Whisper is not wanted.
 */
@Composable
private fun EngineChooser(cloudProvider: CloudProvider?, onChoose: (EngineChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigChoice(
                icon = AppIcons.Phone,
                title = "Local",
                subtitle = "Whisper, on this phone",
                primary = true,
                onClick = { onChoose(EngineChoice.WHISPER_Q8) },
                modifier = Modifier.weight(1f),
            )
            BigChoice(
                icon = AppIcons.Cloud,
                title = "Cloud",
                subtitle = cloudProvider?.label ?: "Add a key first",
                primary = false,
                onClick = { onChoose(EngineChoice.CLOUD) },
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = { onChoose(EngineChoice.ANDROID_SR) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Use Android's recognizer")
        }
    }
}

@Composable
private fun BigChoice(
    icon: ImageVector,
    title: String,
    subtitle: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
    val shape = MaterialTheme.shapes.extraLarge
    val padding = PaddingValues(horizontal = 12.dp, vertical = 18.dp)
    if (primary) {
        Button(onClick = onClick, shape = shape, contentPadding = padding, modifier = modifier, content = content)
    } else {
        FilledTonalButton(onClick = onClick, shape = shape, contentPadding = padding, modifier = modifier, content = content)
    }
}

@Composable
private fun TranscriptBody(
    transcript: TranscribeState?,
    cloudProvider: CloudProvider?,
    onTranscribe: () -> Unit,
) {
    val context = LocalContext.current
    when (transcript) {
        null -> Unit

        is TranscribeState.Running -> RunningTranscription(transcript)

        is TranscribeState.Failed -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Transcription failed (${engineLabelForId(transcript.engineId, cloudProvider)})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        transcript.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            FilledTonalButton(onClick = onTranscribe) {
                Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Retry")
            }
        }

        is TranscribeState.Done -> {
            transcript.note?.let { note ->
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = transcript.text.ifBlank { "No speech recognised in this note." },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                "${engineLabelForId(transcript.engineId, cloudProvider)} · ${formatDuration(transcript.wallMs)} · " +
                    "${"%.2f".format(transcript.realTimeFactor)}× real time · ${wordCount(transcript.text)} words",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { copyToClipboard(context, transcript.text) }, enabled = transcript.text.isNotBlank()) {
                    Icon(AppIcons.Copy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Copy")
                }
                FilledTonalButton(onClick = { shareText(context, transcript.text) }, enabled = transcript.text.isNotBlank()) {
                    Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Share")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onTranscribe) { Icon(AppIcons.Refresh, contentDescription = "Transcribe again") }
            }
        }
    }
}

/**
 * What a long transcription is doing right now: which stage, how far, how long is left, and the
 * words recognised so far, so a wait of a minute or two reads as progress rather than a hang.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RunningTranscription(running: TranscribeState.Running) {
    val fraction = running.fraction
    val percent = fraction?.let { " · ${(it * 100).roundToInt()}%" } ?: ""
    val headline = when (running.stage) {
        TranscribeState.Stage.Decoding -> "Reading the audio$percent"
        TranscribeState.Stage.Preparing -> (running.detail ?: "Preparing") + percent
        TranscribeState.Stage.Transcribing -> "Transcribing$percent"
    }
    Text(headline, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

    val bar = Modifier.fillMaxWidth()
    if (fraction == null) LinearWavyProgressIndicator(bar) else LinearWavyProgressIndicator(progress = { fraction }, modifier = bar)

    val since = running.transcribingSince
    val audioMs = running.audioMs
    if (running.stage == TranscribeState.Stage.Transcribing && since != null && audioMs != null && fraction != null) {
        val elapsed = SystemClock.elapsedRealtime() - since
        // Too noisy to estimate in the first couple of percent.
        val remaining = if (fraction > 0.02f) (elapsed * (1 - fraction) / fraction).toLong() else null
        Text(
            text = "${formatClock((fraction * audioMs).toLong())} of ${formatClock(audioMs)} heard" +
                (remaining?.let { " · about ${formatClock(it)} left" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    running.partialText?.takeIf { it.isNotBlank() }?.let { text ->
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The file's technical side, and decoding to a WAV that can be played back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(
    clip: SharedClip,
    decode: DecodeState?,
    isPlaying: Boolean,
    onDecode: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(clip.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Field("Duration", formatDuration(clip.durationMs))
            Field("Container (header)", describeContainer(clip))
            Field("MIME claimed", clip.declaredMime ?: "none given")
            Field("Track codec", clip.trackMime ?: "unknown")
            Field("Sample rate", clip.sampleRateHz?.let { "$it Hz" } ?: "unknown")
            Field("Channels", clip.channelCount?.toString() ?: "unknown")
            Field("Size", formatSize(clip.sizeBytes))
            if (clip.readError == null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                DecodeSection(decode, isPlaying, onDecode, onPlay, onStop)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DecodeSection(
    decode: DecodeState?,
    isPlaying: Boolean,
    onDecode: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    when (decode) {
        null -> OutlinedButton(onClick = onDecode) { Text("Decode to 16 kHz") }

        is DecodeState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(32.dp))
            Text("Decoding… ${(decode.fraction * 100).roundToInt()}%", Modifier.padding(start = 8.dp))
        }

        is DecodeState.Failed -> {
            Problem("Decode failed", decode.message)
            OutlinedButton(onClick = onDecode) { Text("Retry") }
        }

        is DecodeState.Done -> {
            val summary = decode.summary
            Field("Decoded", "${summary.sampleRate} Hz mono, ${formatDuration(summary.durationMs)}")
            Field("Decoder output", "${summary.sourceRate} Hz, ${summary.sourceChannels} ch, ${summary.codecMime}")
            Field("Peak", "%.2f".format(summary.peak))
            Field("Time", "decode ${summary.decodeMs} ms, resample ${summary.resampleMs} ms")
            Field("WAV", summary.wavFile.name)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPlaying) Button(onClick = onStop) { Text("Stop") } else Button(onClick = onPlay) { Text("Play WAV") }
                OutlinedButton(onClick = onDecode) { Text("Decode again") }
            }
        }
    }
}

/** What the settings sheet can ask of the cloud setup. */
data class CloudActions(
    /** Lists the models a key offers; null uses the saved key. */
    val find: (key: String?) -> Unit,
    val save: (provider: CloudProvider, model: String, key: String?) -> Unit,
    val removeKey: () -> Unit,
    val dismissModels: () -> Unit,
)

/** The provider the cloud engine will use, or null while no key is saved. */
private val IntakeState.cloudProvider: CloudProvider?
    get() = cloud?.takeIf { it.hasKey }?.provider

/**
 * The cloud's key and model; the engine itself is chosen on each note. There is no provider to
 * pick: the key says which service it belongs to, and then lists the models it can use. The key
 * is write-only here: once saved it is only ever replaced or removed, never shown.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsSheet(
    state: IntakeState,
    cloud: CloudActions,
    onDismiss: () -> Unit,
) {
    val saved = state.cloud?.takeIf { it.hasKey }
    var key by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = {
            cloud.dismissModels()
            onDismiss()
        },
        sheetState = rememberFullSheetState(),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Cloud", style = MaterialTheme.typography.titleLarge)
            Text(
                "Used only when you choose it, and only after you confirm each note. The audio leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (saved != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(saved.provider.label, style = MaterialTheme.typography.titleMedium)
                        Text(saved.model, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { cloud.find(null) }) { Text("Change model") }
                            TextButton(onClick = cloud.removeKey) { Text("Remove key") }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (saved != null) "New API key" else "API key") },
                supportingText = { Text("OpenRouter, OpenAI, Groq or Mistral; the key says which.") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { cloud.find(key) },
                enabled = key.isNotBlank() && state.cloudModels != CloudModels.Loading,
            ) { Text("Find models") }

            when (val models = state.cloudModels) {
                null -> Unit
                CloudModels.Loading -> Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Text("Checking the key", style = MaterialTheme.typography.bodyMedium)
                }
                is CloudModels.Failed -> Text(
                    models.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
                is CloudModels.Loaded -> ModelPicker(
                    loaded = models,
                    current = saved?.takeIf { it.provider == models.provider }?.model,
                    onSave = { model ->
                        cloud.save(models.provider, model, key.ifBlank { null })
                        key = ""
                    },
                )
            }
        }
    }
}

/**
 * The models a key offers, with a search box when the list is long (OpenRouter has about 30).
 * The provider's suggested model comes first and is picked unless one was saved before.
 */
@Composable
private fun ModelPicker(loaded: CloudModels.Loaded, current: String?, onSave: (String) -> Unit) {
    var selected by rememberSaveable(loaded) {
        mutableStateOf(current?.takeIf { id -> loaded.models.any { it.id == id } } ?: loaded.models.first().id)
    }
    var query by rememberSaveable(loaded) { mutableStateOf("") }
    val shown = loaded.models.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
    }

    Text(
        "${loaded.provider.label} · ${loaded.models.size} transcription models",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
    if (loaded.models.size > 8) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search models") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    for (model in shown) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .selectable(selected = model.id == selected, role = Role.RadioButton) { selected = model.id }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = model.id == selected, onClick = null)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.bodyLarge)
                val details = listOfNotNull(
                    model.id.takeIf { model.name != it },
                    "suggested".takeIf { model.id == loaded.provider.suggestedModel },
                )
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (shown.isEmpty()) {
        Text("No model matches.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    }
    Button(onClick = { onSave(selected) }, modifier = Modifier.padding(top = 8.dp)) { Text("Save") }
}

/**
 * A sheet that opens fully rather than stopping half-way: both of ours are short forms. The set
 * lists the states the sheet may take (not the ones it skips); it must include Expanded, or the
 * sheet throws the moment it is composed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberFullSheetState() =
    rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))

private fun engineLabel(engine: EngineChoice, cloud: CloudProvider?): String = when (engine) {
    EngineChoice.WHISPER_Q8 -> "Whisper"
    EngineChoice.ANDROID_SR -> "Android's recognizer"
    EngineChoice.CLOUD -> "Cloud" + (cloud?.let { " · ${it.label}" } ?: "")
}

private fun engineLabelForId(id: String, cloud: CloudProvider?): String =
    EngineChoice.entries.firstOrNull { it.id == id }?.let { engineLabel(it, cloud) } ?: id

private val WHATSAPP_NAME = Regex("""(?:PTT|AUD)-(\d{4})(\d{2})(\d{2})-WA\d+""")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

private fun friendlyTitle(clip: SharedClip): String = friendlyTitle(clip.displayName)

/** "PTT-20261031-WA0009.opus" reads better as "Voice note · 31 Oct 2026". */
internal fun friendlyTitle(fileName: String): String {
    WHATSAPP_NAME.find(fileName)?.let { match ->
        val (year, month, day) = match.destructured
        runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()?.let { date ->
            return "Voice note · ${DATE_FORMAT.format(date)}"
        }
    }
    return fileName.substringBeforeLast('.')
}

internal fun copyToClipboard(context: Context, text: String) {
    // Android 13+ confirms the copy with its own on-screen chip.
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Transcript", text))
}

internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

/** Never reports "unrecognised" for a file whose bytes were never read. */
private fun describeContainer(clip: SharedClip): String = when {
    clip.container == null -> "not read"
    clip.container.isDecodeTarget -> clip.container.label
    else -> clip.container.label + " — not a tested format"
}

@Composable
private fun Problem(what: String, detail: String) {
    Text(
        text = "$what: $detail",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Takes the remaining width so a long value wraps instead of squeezing the label.
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
    }
}

private val WHITESPACE = Regex("\\s+")

private fun wordCount(text: String): Int = text.split(WHITESPACE).count { it.isNotEmpty() }

/** m:ss, for durations a person reads at a glance. */
internal fun formatClock(ms: Long): String {
    val seconds = (ms + 500) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatDuration(ms: Long?): String {
    if (ms == null) return "unknown"
    val totalSeconds = ms / 1000.0
    val minutes = (totalSeconds / 60).toInt()
    val seconds = totalSeconds - minutes * 60
    return if (minutes > 0) {
        "%d min %04.1f s".format(minutes, seconds)
    } else {
        "%.1f s".format(seconds)
    }
}

private fun formatSize(bytes: Long?): String = when {
    bytes == null -> "unknown"
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
