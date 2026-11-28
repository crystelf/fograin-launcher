package com.fograin.launcher.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommunityComposerStateTest {
    @Test
    fun hashtagsAreNormalizedAndAppendedOnlyWhenEnabled() {
        val composer = CommunityComposerUiState(
            text = "Hello world",
            hashtagsEnabled = true,
            hashtags = "#Fograin  CrystelfLab, launch",
        )
        assertEquals("Hello world\n\n#Fograin #CrystelfLab #launch", composer.publishText)

        assertFalse(composer.copy(hashtagsEnabled = false).publishText.contains("#Fograin"))
    }

    @Test
    fun hashtagsAloneAreAValidPost() {
        val composer = CommunityComposerUiState(
            hashtagsEnabled = true,
            hashtags = "Fograin",
        )
        assertEquals("#Fograin", composer.publishText)
        assertTrue(composer.hasPayload)
    }

    @Test
    fun pollRequiresTwoDistinctNonBlankChoices() {
        val base = CommunityComposerUiState(pollEnabled = true)
        assertFalse(base.copy(pollChoices = listOf("", "")).pollChoicesValid)
        assertFalse(base.copy(pollChoices = listOf("Yes", "Yes")).pollChoicesValid)
        assertTrue(base.copy(pollChoices = listOf("Yes", "No")).pollChoicesValid)
    }

    @Test
    fun pollAloneIsAValidPost() {
        val composer = CommunityComposerUiState(
            pollEnabled = true,
            pollChoices = listOf("A", "B"),
        )
        assertTrue(composer.hasPayload)
    }
}
