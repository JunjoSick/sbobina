package dev.junjo.eztranscribe.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import dev.junjo.eztranscribe.engine.CloudProvider
import dev.junjo.eztranscribe.share.SharedClip
import kotlinx.coroutines.flow.StateFlow

/**
 * The screen's handle on [TranscriptionHub]. Holds nothing itself: the work belongs to the
 * process, so it survives this screen being closed, and a new screen picks it back up.
 */
class IntakeViewModel(application: Application) : AndroidViewModel(application) {

    private val hub = TranscriptionHub.get(application)

    val state: StateFlow<IntakeState> = hub.state

    fun onIntent(intent: Intent?) = hub.onIntent(intent)

    fun decode(clip: SharedClip) = hub.decode(clip)

    fun selectEngine(choice: EngineChoice) = hub.selectEngine(choice)

    fun saveCloudSettings(provider: CloudProvider, model: String, apiKey: String?) =
        hub.saveCloudSettings(provider, model, apiKey)

    fun findCloudModels(key: String?) = hub.findCloudModels(key)

    fun removeCloudKey() = hub.removeCloudKey()

    fun dismissCloudModels() = hub.dismissCloudModels()

    fun transcribe(clip: SharedClip, choice: EngineChoice) = hub.transcribe(clip, choice)

    fun deleteFromHistory(id: String) = hub.deleteFromHistory(id)

    fun clearHistory() = hub.clearHistory()

    fun play(clip: SharedClip) = hub.play(clip)

    fun stopPlayback() = hub.stopPlayback()
}
