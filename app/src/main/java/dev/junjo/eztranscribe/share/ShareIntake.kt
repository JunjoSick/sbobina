package dev.junjo.eztranscribe.share

import android.content.Intent
import android.net.Uri

/** Pulls the shared audio out of the intents declared in the manifest. */
object ShareIntake {

    fun urisFrom(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))

        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

        else -> emptyList()
    }
}
