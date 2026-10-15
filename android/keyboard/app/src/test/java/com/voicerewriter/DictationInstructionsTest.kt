package com.voicerewriter
import org.junit.Assert.*
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class DictationInstructionsTest {
 @Test fun splitsInstructionAndResumedSpeech() {
  val parsed=DictationInstructions.parse("Alpha. Transcription instructions: lowercase. Resume dictation. Beta.")
  assertTrue(parsed.requested)
  assertEquals(listOf("dictation","instruction","dictation"),parsed.segments.map{it.kind})
  assertEquals(listOf("Alpha.","lowercase.","Beta."),parsed.segments.map{it.text})
 }
 @Test fun literalAndQuotedStayContent() {
  for(text in listOf("Write the literal words transcription instructions.","The phrase transcription instructions means a command.","Say \"transcription instructions\" aloud."))assertFalse(DictationInstructions.requested(text))
 }
 @Test fun singleQuotesAndContractions() {
  assertFalse(DictationInstructions.requested("He called it 'transcription instructions' yesterday."))
  assertFalse(DictationInstructions.requested("He called it ‘transcription instructions’ yesterday."))
  assertTrue(DictationInstructions.requested("Don't remove this. Transcription instructions: make it lowercase."))
 }
 @Test fun emptyStructuredResultIsValid(){assertEquals("",DictationInstructions.readResponse("{\"text\":\"\"}"))}
 @Test fun ordinaryRequestsStayContent(){assertFalse(DictationInstructions.requested("Please remove the budget paragraph."))}
 @Test fun explicitRemovalBypassesCleanupDropGuard(){assertTrue(DictationInstructions.accepts("Delete this. Transcription instructions: remove all of this dictation.",""))}
 @Test fun unrequestedRemovalIsRejected(){assertFalse(DictationInstructions.accepts("Keep all these words in my important sentence.",""))}
}
