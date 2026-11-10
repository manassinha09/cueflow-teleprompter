package com.novaflow.cueflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptMatcherTest {
    @Test
    fun recoversZipformerEndingAfterFinalizationOnlyWhenAnchored() {
        val script = "Stop recording and play back the saved video."
        val matcher = ScriptMatcher(script)
        val anchor = "stop recording and play back the"
        assertEquals(script.indexOf(" saved"), matcher.advance(anchor))
        assertNull(matcher.advance("$anchor safe vitio"))
        assertNull(matcher.advance("$anchor same music", finalized = true))
        assertNull(matcher.advance("safe vitio", finalized = true))
        assertNull(matcher.advance("$anchor safe vitio"))
        assertEquals(script.lastIndexOf('.'), matcher.advance("$anchor safe vitio", finalized = true))
    }

    @Test
    fun continuesWithIndividualWordsOnceTheReadingPositionIsEstablished() {
        val script = "Welcome to our camera test today."
        val matcher = ScriptMatcher(script)
        assertEquals(script.indexOf(" our"), matcher.advance("welcome to"))
        assertEquals(script.indexOf(" camera"), matcher.advance("our"))
        assertEquals(script.indexOf(" test"), matcher.advance("our camera"))
        assertEquals(script.indexOf(" today"), matcher.advance("our camera test"))
    }

    @Test
    fun toleratesOneDroppedWordOrSubstitutionAndSpokenFillers() {
        val script = "The script should move as you read aloud."
        assertEquals(script.lastIndexOf('.'), ScriptMatcher(script).advance("the script moves as you read aloud"))
        assertEquals(script.lastIndexOf('.'), ScriptMatcher(script).advance("the script should move as you uh read aloud"))
        assertEquals(script.lastIndexOf('.'), ScriptMatcher(script).advance("the script should move you read aloud"))
    }

    @Test
    fun normalizesNumbersAndContractionsWithoutChangingHighlightOffsets() {
        val script = "We're recording 30 frames. It won't take 42 minutes."
        val matcher = ScriptMatcher(script)
        assertEquals(script.indexOf('.'), matcher.advance("we are recording thirty frames"))
        assertEquals(script.lastIndexOf('.'), matcher.advance("it will not take forty two minutes"))
    }

    @Test
    fun unrelatedWordsAndAmbiguousFuzzyMatchesNeverSeekForward() {
        val matcher = ScriptMatcher("We should read this line. Pause here. We should read this line.")
        assertNull(matcher.advance("we read this line"))
        assertNull(matcher.advance("read"))
        assertNull(matcher.advance("this line"))
        assertEquals(0, matcher.position)
    }

    @Test
    fun neverHighlightsAnUnheardTrailingWord() {
        val script = "Read the next sentence slowly."
        val matcher = ScriptMatcher(script)
        assertEquals(script.indexOf(" slowly"), matcher.advance("read the next sentence"))
        assertNull(matcher.advance("read the next sentence something unrelated"))
        assertEquals(4, matcher.position)
    }

    @Test
    fun overlappingHypothesesAdvanceWithoutRewindingOnARepeatedLine() {
        val script = "Start with these words. Then read this line. Finally finish here."
        val matcher = ScriptMatcher(script)
        assertEquals(script.indexOf('.'), matcher.advance("Start with these words"))
        assertEquals(script.indexOf("line") + 4, matcher.advance("these words then read this line"))
        assertNull(matcher.advance("Start with these words"))
        assertEquals(8, matcher.position)
    }

    @Test
    fun ambiguousAndUnrelatedSpeechHoldPosition() {
        val matcher = ScriptMatcher("We can start here. Now listen carefully. We can start here.")
        assertNull(matcher.advance("we can start here"))
        assertNull(matcher.advance("the microphone picked up a different conversation"))
        assertEquals(0, matcher.position)
    }

    @Test
    fun aUniqueNearbyPhraseRecoversAfterASkipAndCanFinishWithOneWord() {
        val script = "Let's skip this phrase. Read this section first. Done."
        val matcher = ScriptMatcher(script)
        assertEquals(script.indexOf("first") + 5, matcher.advance("uh read this section first"))
        assertEquals(script.lastIndexOf('.'), matcher.advance("done"))
        assertNull(matcher.advance("done"))
    }

    @Test
    fun matchesSmartApostrophesAndIgnoresPunctuationAndCase() {
        val script = "We’re testing CueFlow, today."
        val matcher = ScriptMatcher(script)
        assertEquals(script.lastIndexOf('.'), matcher.advance("we're testing cueflow today!"))
    }

    @Test
    fun manualRewindAllowsTheSameWordsToBeReadAgain() {
        val script = "Read this first sentence. Continue with another sentence. Finish this last sentence."
        val matcher = ScriptMatcher(script)
        val end = script.indexOf('.')
        assertEquals(end, matcher.advance("read this first sentence"))
        matcher.seekTo(0)
        assertEquals(end, matcher.advance("read this first sentence"))
        matcher.seekTo(script.indexOf("Continue"))
        assertEquals(script.indexOf('.', end + 1), matcher.advance("continue with another sentence"))
    }

    @Test
    fun seekToTheEndAndBackDoesNotLeaveTheMatcherFinished() {
        val script = "Start reading here. Last few words."
        val matcher = ScriptMatcher(script)
        matcher.seekTo(script.length)
        assertNull(matcher.advance("last few words"))
        matcher.seekTo(script.indexOf("Last"))
        assertEquals(script.lastIndexOf('.'), matcher.advance("last few words"))
    }

    @Test
    fun voiceNavigationAcceptsTwoLinePhrasesWithoutMatchingOrdinarySentences() {
        assertEquals(-2, scrollCommandLines("Scroll up two lines."))
        assertEquals(2, scrollCommandLines("Scroll down 2 lines!"))
        assertEquals(2, scrollCommandLines("Scroll down to lines."))
        assertEquals(-2, scrollCommandLines("Scroll up too lines."))
        assertEquals(2, scrollCommandLines("Scrolled down two lines."))
        assertNull(scrollCommandLines("I scrolled down two lines."))
        assertEquals(-2, scrollCommandLines("Okay, scroll two lines up"))
        assertEquals(2, scrollCommandLines("scroll down"))
        assertEquals(-2, scrollCommandLines("scroll up"))
        assertEquals(2, scrollCommandLines("Scroll down two lines. Scroll down two"))
        assertNull(scrollCommandLines("Let's scroll through this text"))
        assertNull(scrollCommandLines("Scroll up to find the answer"))
        assertNull(scrollCommandLines("scroll down three lines"))
    }
}
