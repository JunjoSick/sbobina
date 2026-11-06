package dev.junjo.eztranscribe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderTest {

    @Test
    fun keyPrefixesPickTheProvider() {
        assertEquals(CloudProvider.OPENROUTER, CloudProvider.fromKey("sk-or-v1-abc"))
        assertEquals(CloudProvider.GROQ, CloudProvider.fromKey("gsk_abc"))
        assertEquals(CloudProvider.OPENAI, CloudProvider.fromKey("sk-proj-abc"))
        assertEquals(CloudProvider.OPENAI, CloudProvider.fromKey("sk-abc"))
        assertEquals(CloudProvider.MISTRAL, CloudProvider.fromKey("AbC123xyz"))
    }

    @Test
    fun pastedKeysAreTrimmed() {
        assertEquals(CloudProvider.OPENROUTER, CloudProvider.fromKey("  sk-or-v1-abc\n"))
    }

    @Test
    fun generalModelListsKeepOnlyTranscription() {
        assertTrue(CloudProvider.OPENAI.transcribes("gpt-4o-transcribe"))
        assertTrue(CloudProvider.OPENAI.transcribes("whisper-1"))
        assertFalse(CloudProvider.OPENAI.transcribes("gpt-4o"))
        assertTrue(CloudProvider.GROQ.transcribes("whisper-large-v3-turbo"))
        assertFalse(CloudProvider.GROQ.transcribes("llama-3.3-70b"))
        assertTrue(CloudProvider.MISTRAL.transcribes("voxtral-mini-latest"))
        assertFalse(CloudProvider.MISTRAL.transcribes("voxtral-mini-transcribe-realtime-2602"))
        assertFalse(CloudProvider.MISTRAL.transcribes("mistral-large-latest"))
    }
}
