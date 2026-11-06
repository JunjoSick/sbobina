package dev.junjo.eztranscribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.junjo.eztranscribe.ui.CloudActions
import dev.junjo.eztranscribe.ui.HistoryActions
import dev.junjo.eztranscribe.ui.IntakeScreen
import dev.junjo.eztranscribe.ui.IntakeViewModel
import dev.junjo.eztranscribe.ui.TranscriptionHub
import dev.junjo.eztranscribe.ui.theme.TranscribeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: IntakeViewModel by viewModels()

    // The answer does not matter for transcribing: without it, only the progress notification is
    // hidden, and transcription still runs in the background.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.onIntent(intent)
        setContent {
            TranscribeTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                IntakeScreen(
                    state = state,
                    onDecode = viewModel::decode,
                    onPlay = viewModel::play,
                    onStop = viewModel::stopPlayback,
                    onTranscribe = { clip, engine ->
                        askForNotificationsOnce()
                        viewModel.transcribe(clip, engine)
                    },
                    cloudActions = CloudActions(
                        find = viewModel::findCloudModels,
                        save = viewModel::saveCloudSettings,
                        removeKey = viewModel::removeCloudKey,
                        dismissModels = viewModel::dismissCloudModels,
                    ),
                    historyActions = HistoryActions(
                        delete = viewModel::deleteFromHistory,
                        clear = viewModel::clearHistory,
                    ),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TranscriptionHub.get(this).screenStarted()
    }

    override fun onStop() {
        TranscriptionHub.get(this).screenStopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.onIntent(intent)
    }

    /** Asked the first time something is transcribed, not at launch, when the reason is clear. */
    private fun askForNotificationsOnce() {
        if (askedForNotifications) return
        askedForNotifications = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        // Once per process: declining is respected until the app is next started.
        var askedForNotifications = false
    }
}
