package com.voicerewriter

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import com.voicerewriter.textproc.TextProcessingConfig
import com.voicerewriter.textproc.TextProcessor
import com.voicerewriter.textproc.VocabCorrector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Voice engine shared by the unified IME and history retry. Never starts without a tap. */
object ImeDictationPipeline {
    /** Runs the same processing path with a shell-supplied synthetic fixture, without history or insertion. */
    suspend fun verifyPipeline(context:Context,forceOffline:Boolean=false):org.json.JSONObject = withContext(Dispatchers.IO) {
        check(work?.isActive!=true && recorder?.isRecording!=true) { "Dictation is busy" }
        val file=java.io.File(context.getExternalFilesDir(null),"pipeline_fixture.wav")
        require(file.length() in 45L..960044L)
        val samples=WavIo.read(file) ?: error("Missing fixture")
        val settings=SettingsRepository(context).get()
        val pending=PendingAudio.begin(context,samples,samples.size/16000,"synthetic.pipeline.fixture","Synthetic pipeline fixture",settings.sttProvider,settings.sttModelResolved) ?: error("Could not stage fixture")
        val started=android.os.SystemClock.elapsedRealtime()
        val stages=org.json.JSONArray()
        try {
            val testSettings=if(forceOffline)settings.copy(sttEndpoint="https://127.0.0.1:1/v1/audio/transcriptions") else settings
            val result=process(context,pending,null,settingsOverride=testSettings){stages.put(it)}
            org.json.JSONObject().put("raw",result.first).put("cleaned",result.second)
                .put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started)
                .put("stages",stages).put("speech_model",settings.sttModelResolved).put("cleanup_model",settings.model)
        } finally { PendingAudio.discard(context,pending.id) }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    /** One model preload at a time; input-view recreation must not queue duplicate 6s loads. */
    @Volatile private var warmJob:Job?=null
    @Volatile private var startJob:Job?=null
    @Volatile private var captureCleanup:Job?=null
    @Volatile private var operationToken=0L

    /** Load local speech while the keyboard is visible, before the user finishes dictating. */
    @Synchronized
    fun warm(context:Context) {
        if(warmJob?.isActive==true)return
        val app=context.applicationContext
        warmJob=scope.launch(Dispatchers.IO) {
            runCatching {
                val settings=SettingsRepository(app).get()
                if(settings.sttProvider=="local" && OnDeviceStt.isReady(app,settings.sttModel))
                    OnDeviceStt.warm(app,settings.sttModel)
            }
        }
    }
    @Volatile private var recorder:AudioRecorder?=null
    private var activeHost:VoiceInputHost?=null
    private var activeSession:VoiceEditorSession?=null
    private var activeSelection:VoiceSelectionTarget?=null
    private var startedAt=0L
    private var work:Job?=null
    private var activePending:String?=null
    private var discardActive=AtomicBoolean(false)
    /** Editor that receives a finished take; follows the keyboard when it reopens mid-take. */
    @Volatile private var commitSession:VoiceEditorSession?=null
    /** Sticky for the whole take: any private (incognito) field it touched keeps it out of history and sync. */
    private class Take(@Volatile var incognito:Boolean)
    /** Per-take state; each async path keeps its own reference, so a new take never resets an old one's. */
    @Volatile private var take=Take(false)
    /** A finished take that could not be inserted because the keyboard was hidden. */
    private class Held(val text:String,val packageName:String,val fieldId:Int,val at:Long)
    @Volatile private var held:Held?=null
    @Volatile private var appContext:Context?=null

    /** Cheap UI poll for the live microphone meter. */
    fun recordingLevel():Float=recorder?.recordingLevel() ?: 0f

    @Synchronized
    fun toggle(host:VoiceInputHost,session:VoiceEditorSession){
        toggleInternal(host,session,null)
    }

    /** Start or stop a recording whose transcript is an instruction for the current selection. */
    @Synchronized
    fun toggleSelectionEdit(host:VoiceInputHost,target:VoiceSelectionTarget){
        if(target.session.password){host.setVoiceStatus("Voice is disabled in password fields");return}
        if(!host.isSelectionCurrent(target)){
            host.setVoiceStatus("Selection changed. Select text again.")
            return
        }
        toggleInternal(host,target.session,target)
    }

    private fun toggleInternal(host:VoiceInputHost,session:VoiceEditorSession,selection:VoiceSelectionTarget?){
        if(session.password){host.setVoiceStatus("Voice is disabled in password fields");return}
        val current=recorder
        if(current!=null && current.isRecording){stop(host,session,current);return}
        if(startJob?.isActive==true)return
        if(captureCleanup?.isActive==true)return
        if(work?.isActive==true){host.setVoiceStatus("Processing. Cancel before starting another recording.");return}
        activeHost=host;activeSession=session;activeSelection=selection;startedAt=System.currentTimeMillis()
        val app=host.voiceContext.applicationContext
        appContext=app;held=null
        val thisTake=Take(session.incognito);take=thisTake
        val next=AudioRecorder(app)
        recorder=next
        val token=++operationToken
        // Plain dictation keeps recording when the keyboard hides; that needs a microphone
        // foreground service, which Android only allows to start while the keyboard is visible.
        if(selection==null)DictationForegroundService.start(app)
        host.setVoiceStatus("Starting")
        startJob=scope.launch(Dispatchers.IO) {
            val started=runCatching{next.start(vadAutoStop=false)}
            val keep=withContext(NonCancellable+Dispatchers.Main.immediate) {
                val currentStart=started.isSuccess && operationToken==token && recorder===next && activeSelection==selection
                if(currentStart){
                    startJob=null
                    host.setVoiceStatus("Listening",true)
                }else if(operationToken==token && recorder===next){
                    recorder=null;startJob=null;activeHost=null;activeSession=null;activeSelection=null
                    DictationForegroundService.stop(app)
                    if(started.isFailure)host.setVoiceStatus("Allow microphone access in app settings")
                }
                currentStart
            }
            // A lifecycle change can race AudioRecord construction. Clean up a recorder that
            // finished starting after its session was invalidated, and preserve that take when
            // lifecycle cancellation asked us to keep it for history retry.
            if(!keep&&started.isSuccess){
                val samples=withContext(NonCancellable+Dispatchers.IO){next.stop()}
                if(selection==null&&!discardActive.get()&&!thisTake.incognito&&samples!=null)
                    preserveForRetry(app,samples,session,((System.currentTimeMillis()-startedAt)/1000).toInt().coerceAtLeast(1))
            }
        }
    }
    private fun appLabel(context:Context,pkg:String):String=runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault(pkg)
    /** Keep a just-captured take recoverable when the input view disappears mid-stop. */
    private suspend fun preserveForRetry(context:Context,samples:ShortArray,session:VoiceEditorSession,duration:Int,cancelled:Boolean=false){
        withContext(NonCancellable+Dispatchers.IO){
            val settings=SettingsRepository(context).get()
            PendingAudio.begin(context,samples,duration,session.packageName,appLabel(context,session.packageName),settings.sttProvider,settings.sttModelResolved)?.let{if(cancelled)PendingAudio.markCancelled(context,it.id) else PendingAudio.release(it.id)}
        }
    }
    /** An explicit Cancel keeps the audio unless it was trivially short or the user wants no history. */
    private fun keepCancelled(context:Context,duration:Int)=duration>=2&&DictationHistory.keepHistory(context)
    private fun stop(host:VoiceInputHost,session:VoiceEditorSession,recording:AudioRecorder,fromNotification:Boolean=false){
        recorder=null
        commitSession=session
        val thisTake=take
        if(session.incognito)thisTake.incognito=true
        fun private()=thisTake.incognito
        val selection=activeSelection
        val context=host.voiceContext.applicationContext
        val duration=((System.currentTimeMillis()-startedAt)/1000).toInt().coerceAtLeast(1)
        // AudioRecord.stop(), worker join and sample assembly can all wait or allocate. Keep the
        // tap handler responsive and show an explicit busy state while that work moves to IO.
        host.setVoiceStatus("Stopping")
        val discardFlag=AtomicBoolean(false);discardActive=discardFlag
        work=scope.launch{
            var samples:ShortArray?=null
            var pending:PendingRecording?=null
            var stagingAttempted=false
            var committed=false
            try{
                // Keep the assignment inside the non-cancellable region. If cancellation lands
                // while the dispatcher resumes, the captured buffer still reaches the recovery
                // branch below instead of being lost between the block and the assignment.
                withContext(NonCancellable+Dispatchers.IO){samples=recording.stop();DictationForegroundService.stop(context)}
                ensureActive()
                if(samples==null){host.setVoiceStatus("No speech captured");return@launch}
                host.setVoiceStatus("Transcribing")
                val settings=SettingsRepository(context).get()
                stagingAttempted=true
                // The pending row is likewise assigned before leaving NonCancellable, so a
                // lifecycle cancellation can release/discard the durable row deterministically.
                withContext(NonCancellable+Dispatchers.IO){pending=PendingAudio.begin(context,samples!!,duration,session.packageName,appLabel(context,session.packageName),settings.sttProvider,settings.sttModelResolved)}
                val record=pending ?: throw IllegalStateException("Could not save recording")
                activePending=record.id
                ensureActive()
                val result=if(selection==null){
                    process(context,record,host.translationTarget(),host.cleanupMode()!="raw",settingsOverride=settings,samplesOverride=samples){host.setVoiceStatus(it)}
                }else{
                    processSelectionInstruction(context,record,selection,settingsOverride=settings,samplesOverride=samples){host.setVoiceStatus(it)}
                }
                ensureActive()
                val target=commitSession ?: session
                val inserted=if(selection==null){
                    result.second.isEmpty() || (host.isSessionCurrent(target)&&host.commitVoiceText(target,result.second))
                }else{
                    host.isSelectionCurrent(selection)&&host.commitSelectionEdit(selection,result.second)
                }
                committed=inserted&&result.second.isNotEmpty()
                if(private()||(selection?.session ?: session).incognito){
                    // Incognito editors: nothing is kept or synced, and the audio is deleted.
                    withContext(NonCancellable+Dispatchers.IO){PendingAudio.discard(context,record.id)}
                }else if(selection==null){
                    withContext(NonCancellable+Dispatchers.IO){saveResult(context,record,result.first,result.second,inserted)}
                }else{
                    // Keep a finished edit recoverable even if the cursor or document changed.
                    withContext(NonCancellable+Dispatchers.IO){
                        DictationHistory.recordDurably(context,DictationEntry(id=record.id,timestamp=record.timestamp,
                            appPackage=record.appPackage,appLabel=record.appLabel + " · Voice edit",durationSec=record.durationSec,
                            words=result.second.split(Regex("\\s+")).count{it.isNotBlank()},accepted=inserted,
                            onDevice=false,before=selection.text,after=result.second))
                        PendingAudio.discard(context,record.id)
                    }
                }
                activePending=null
                if(inserted){
                    host.setVoiceStatus("Ready")
                    host.returnToVoice()
                }else if(selection!=null){
                    host.setVoiceStatus("Selection changed. Edit saved to history.")
                }else{
                    // The keyboard was hidden when the take finished: insert it when the keyboard
                    // reopens on the same field, and put it on the clipboard when finished from
                    // the notification, where there is no field to type into.
                    if(!private())held=Held(result.second,target.packageName,target.fieldId,System.currentTimeMillis())
                    if(fromNotification&&!private())copyToClipboard(context,result.second)
                    host.setVoiceStatus(if(private())"Field changed. Not inserted." else "Saved to history. Copy when ready.")
                    host.returnToVoice()
                }
            }catch(_:CancellationException){
                // If cancellation won before the write-ahead stage, retain the in-memory take so
                // a disappearing input view cannot silently lose a recording. Once staging was
                // attempted, the existing row is the durable source of truth.
                val userCancel=discardFlag.get()
                if(selection==null&&!private()&&pending==null&&!stagingAttempted&&samples!=null&&(!userCancel||keepCancelled(context,duration)))
                    preserveForRetry(context,samples!!,session,duration,cancelled=userCancel)
                pending?.let{withContext(NonCancellable+Dispatchers.IO){
                    if(selection!=null||private()||(userCancel&&!keepCancelled(context,duration)))PendingAudio.discard(context,it.id)
                    else if(userCancel)PendingAudio.markCancelled(context,it.id)
                    else PendingAudio.release(it.id)}}
            }catch(e:Exception){
                val keep=selection==null&&!private()
                pending?.let{withContext(NonCancellable+Dispatchers.IO){if(keep)PendingAudio.release(it.id) else PendingAudio.discard(context,it.id)}}
                // The text already landed; only the history write failed. Never report that as
                // a failed take, which invites a retry that would paste it twice.
                if(committed){host.setVoiceStatus("Inserted. History save failed.");host.returnToVoice();return@launch}
                host.setVoiceStatus(when{selection!=null->"Edit failed. Original kept."
                    private()->"Failed: ${e.message.orEmpty().take(90)}"
                    else->"Saved for retry: ${e.message.orEmpty().take(90)}"})
            }finally{activePending=null;activeSelection=null;commitSession=null}
        }
    }

    private fun copyToClipboard(context:Context,text:String){
        runCatching{
            val clipboard=context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Dictation",text))
        }
    }

    /**
     * The keyboard is going away (app switch, collapse, rotation). A plain dictation keeps
     * recording, and a finished take keeps processing; everything else stops as before.
     */
    @Synchronized
    fun detach(){
        val busy=recorder!=null||startJob?.isActive==true||work?.isActive==true
        if(busy&&activeSelection==null)return
        cancel(false)
    }

    /** The keyboard is back on [session]: the in-progress take now belongs to this field. */
    @Synchronized
    fun adopt(host:VoiceInputHost,session:VoiceEditorSession){
        if(activeSelection!=null||session.password)return
        if(recorder!=null){
            if(session.incognito)take.incognito=true
            activeHost=host;activeSession=session
            if(startJob?.isActive==true)host.setVoiceStatus("Starting") else host.setVoiceStatus("Listening",true)
            return
        }
        if(work?.isActive==true){
            // A stopped take is only ever inserted back into the field it was stopped in, never another app.
            val owner=commitSession
            if(owner!=null&&owner.packageName==session.packageName&&owner.fieldId==session.fieldId){commitSession=session;host.setVoiceStatus("Transcribing")}
            return
        }
        val h=held ?: return
        held=null
        if(System.currentTimeMillis()-h.at>5*60_000||h.packageName!=session.packageName||h.fieldId!=session.fieldId)return
        if(host.commitVoiceText(session,h.text))host.setVoiceStatus("Ready")
    }

    /** Notification "Finish": stop the hidden take and process it to history (and the clipboard). */
    @Synchronized
    fun finishDetached(){
        val current=recorder ?: return
        val host=activeHost ?: return
        val session=activeSession ?: return
        if(!current.isRecording||startJob?.isActive==true)return
        stop(host,session,current,fromNotification=true)
    }
    private suspend fun process(context:Context,pending:PendingRecording,target:String?,cleanup:Boolean=true,settingsOverride:Settings?=null,samplesOverride:ShortArray?=null,status:(String)->Unit):Pair<String,String>{
        val settings=settingsOverride ?: SettingsRepository(context).get()
        val samples=samplesOverride ?: withContext(Dispatchers.IO){PendingAudio.samples(context,pending.id)} ?: throw IllegalStateException("Saved audio is unavailable")
        val vocab=VocabRepository(context).get()
        val mobileRules=MobileRulesRepository(context).get()
        val bias=withContext(Dispatchers.Default){VocabCorrector.biasPrompt(vocab).ifBlank{null}}
        val raw=transcribe(context,pending,settings,samples,bias,status)
        var cleaned=withContext(Dispatchers.Default){VocabCorrector.correct(raw,vocab)}
        val hasInstructions=DictationInstructions.requested(raw)
        if(cleanup)cleaned=SpokenNumbers.convert(cleaned)
        if(cleanup&&settings.deterministicCleanup&&!hasInstructions)cleaned=withContext(Dispatchers.Default){TextProcessor.process(cleaned,TextProcessingConfig(selfCorrectionEnabled=false,fillerRemovalEnabled=false,listFormattingEnabled=false),isCodeContext=true)}
        if(hasInstructions&&!settings.isConfigured)throw IllegalStateException("Instructions need the cleanup provider. Recording saved.")
        if(hasInstructions||(cleanup&&settings.llmPolishEnabled&&settings.isConfigured&&cleaned.trim().split(Regex("\\s+")).size>=4)){
            status(if(hasInstructions)"Applying instructions" else "Cleaning up")
            try{
                val out=StringBuilder();RewriteEngine.streamDictationCleanup(settings,cleaned,vocab.filter{!it.isSnippet}.map{it.canonical}).collect{out.append(it)}
                val polished=if(hasInstructions)DictationInstructions.readResponse(out.toString()) else out.toString().replace(Regex("</?transcript>"),"").trim()
                if(hasInstructions&&polished.isBlank()&&cleaned.trim().split(Regex("\\s+")).size>=12&&!Regex("(?i)\\b(delete|remove|clear|erase|wipe|get rid)").containsMatchIn(cleaned))
                    throw IllegalStateException("Instructions returned empty text. Recording saved for retry.")
                if(DictationInstructions.accepts(cleaned,polished))cleaned=polished
            }catch(e:CancellationException){throw e}catch(e:Exception){
                // An instruction failure must remain retryable, never paste the command itself.
                if(hasInstructions)throw IllegalStateException("Instructions failed. Recording saved for retry.",e)
                status("Cleanup unavailable. Keeping transcript.")
            }
        }
        if(target!=null){
            if(!settings.isConfigured)throw IllegalStateException("Translation provider is not configured")
            status("Translating")
            val out=StringBuilder();RewriteEngine.stream(settings,"translate_$target",cleaned).collect{out.append(it)}
            cleaned=RewriteEngine.cleanOutput(out.toString()).takeIf{it.isNotBlank()} ?: throw IllegalStateException("Translation returned no text")
        }
        // Dashboard rules are deliberately applied only after every cleanup/translation stage.
        // The single-pass matcher never reprocesses replacement text.
        cleaned=withContext(Dispatchers.Default){MobileRulesCorrector.apply(cleaned,mobileRules)}
        return raw to cleaned
    }

    /** Voice instruction path for the keyboard's selection Edit action. */
    private suspend fun processSelectionInstruction(
        context:Context,
        pending:PendingRecording,
        target:VoiceSelectionTarget,
        settingsOverride:Settings?=null,
        samplesOverride:ShortArray?=null,
        status:(String)->Unit,
    ):Pair<String,String>{
        val settings=settingsOverride ?: SettingsRepository(context).get()
        val samples=samplesOverride ?: withContext(Dispatchers.IO){PendingAudio.samples(context,pending.id)} ?: throw IllegalStateException("Saved audio is unavailable")
        val vocab=VocabRepository(context).get()
        val bias=withContext(Dispatchers.Default){VocabCorrector.biasPrompt(vocab).ifBlank{null}}
        val raw=transcribe(context,pending,settings,samples,bias,status)
        val instruction=withContext(Dispatchers.Default){VocabCorrector.correct(raw,vocab).trim()}
        if(instruction.isBlank())throw IllegalStateException("No edit instruction recognized")
        if(!settings.isConfigured)throw IllegalStateException("Edit provider is not configured")
        return raw to rewriteSelection(settings, instruction, target, status)
    }

    /** PROCESS_TEXT owns its result contract; it never pastes into the current foreground app. */
    suspend fun editSelectionAudio(context:Context,samples:ShortArray,text:String,caller:String,status:(String)->Unit):String {
        require(text.isNotBlank() && text.length<=12000) { "Select up to 12,000 characters." }
        val settings=SettingsRepository(context).get()
        val record=withContext(Dispatchers.IO){PendingAudio.begin(context,samples,(samples.size/16000).coerceAtLeast(1),caller,"Voice edit",settings.sttProvider,settings.sttModelResolved)}
            ?: throw IllegalStateException("Could not save recording")
        try {
            val target=VoiceSelectionTarget(VoiceEditorSession(0,caller,0,false),0,text.length,text)
            val result=processSelectionInstruction(context,record,target,settings,samples,status)
            withContext(Dispatchers.IO){DictationHistory.recordDurably(context,DictationEntry(id=record.id,timestamp=record.timestamp,
                appPackage=caller,appLabel="Voice edit",durationSec=record.durationSec,words=result.second.split(Regex("\\s+")).count{it.isNotBlank()},
                accepted=false,onDevice=false,before=text,after=result.second))}
            return result.second
        } finally { withContext(NonCancellable+Dispatchers.IO){PendingAudio.discard(context,record.id)} }
    }

    private suspend fun rewriteSelection(settings:Settings,instruction:String,target:VoiceSelectionTarget,status:(String)->Unit):String {
        status("Editing")
        val out=StringBuilder()
        RewriteEngine.streamInstruction(settings,instruction,target.text).collect{out.append(it)}
        val result=DictationInstructions.readResponse(out.toString())
        // An empty replacement would wipe the selection; only honor it when the speaker asked to delete.
        if(result.isBlank()&&target.text.isNotBlank()&&!Regex("(?i)\\b(delete|remove|clear|erase|wipe|get rid)").containsMatchIn(instruction))
            throw IllegalStateException("Edit returned empty text. Original kept.")
        return result
    }

    /** Emulator-only, shell-permission-gated QA. Injects a recognized instruction, never opens a mic. */
    fun runSelectionFixture(host:VoiceInputHost,target:VoiceSelectionTarget,instruction:String,delayMs:Long,key:String) {
        check(android.os.Build.HARDWARE in listOf("ranchu","goldfish"))
        check(target.session.packageName=="com.adamholter.openwhispr.qafixture")
        check(work?.isActive!=true && recorder==null)
        val app=host.voiceContext.applicationContext
        val report=java.io.File(app.getExternalFilesDir(null),"selection_fixture_result.json")
        activeHost=host;activeSession=target.session;activeSelection=target
        work=scope.launch {
            val result=org.json.JSONObject()
            try {
                host.setVoiceStatus("Editing")
                delay(delayMs.coerceIn(0,10000))
                if(!host.isSelectionCurrent(target)) {
                    result.put("state","stale");host.setVoiceStatus("Selection changed. Edit saved to history.")
                } else {
                    val settings=SettingsRepository(app).get().copy(provider="openrouter",model="inception/mercury-2.5",apiKey=key)
                    val replacement=rewriteSelection(settings,instruction,target){host.setVoiceStatus(it)}
                    ensureActive()
                    val applied=host.isSelectionCurrent(target)&&host.commitSelectionEdit(target,replacement)
                    result.put("state",if(applied)"applied" else "stale").put("output",replacement)
                    host.setVoiceStatus(if(applied)"Ready" else "Selection changed. Original kept.")
                }
            } catch(_:CancellationException) { result.put("state","cancelled") }
            catch(e:Exception) { result.put("state","failed").put("error",e.javaClass.simpleName);host.setVoiceStatus("Edit failed. Original kept.") }
            finally { report.writeText(result.toString());activeHost=null;activeSession=null;activeSelection=null }
        }
    }

    private suspend fun transcribe(
        context:Context,
        pending:PendingRecording,
        settings:Settings,
        samples:ShortArray,
        bias:String?,
        status:(String)->Unit,
    ):String{
        val sttStarted=android.os.SystemClock.elapsedRealtime()
        var usedFallback=false
        val raw=withContext(Dispatchers.IO){
            if(settings.sttProvider=="local") {
                // Follow the official mobile app's local-engine routing: an already
                // downloaded Whisper model keeps dictation usable if Parakeet is absent.
                val parakeetMissing=OnDeviceStt.isParakeet(OnDeviceStt.resolveModel(settings.sttModel))
                    && !ParakeetModelManager.isReady(context)
                val fallback=parakeetMissing && WhisperModelManager.isReady(context,"base")
                if(fallback){
                    usedFallback=true
                    withContext(Dispatchers.Main.immediate){status("Parakeet unavailable. Using offline speech.")}
                }
                OnDeviceStt.transcribe(context,
                    if(fallback)settings.copy(sttModel="base") else settings,
                    WavIo.toFloats(samples),bias)
            }
            else try { SttEngine.transcribe(settings,PendingAudio.wavFile(context,pending.id),bias) }
            catch(e:CancellationException){throw e}
            catch(e:Exception){
                if(settings.sttModelResolved!="ibm-granite/granite-speech-5.0-470m-turboctc" || !WhisperModelManager.isReady(context,"base"))throw e
                usedFallback=true
                withContext(Dispatchers.Main.immediate){status("Granite unreachable. Using offline speech.")}
                OnDeviceStt.transcribe(context,settings.copy(sttProvider="local",sttModel="base"),WavIo.toFloats(samples),bias)
            }
        }
        android.util.Log.i("DictationSpeech","model=${if(usedFallback)"whisper-base-fallback" else settings.sttModelResolved} elapsed_ms=${android.os.SystemClock.elapsedRealtime()-sttStarted}")
        if(raw.isBlank())throw IllegalStateException("No speech recognized")
        return raw
    }
    private fun saveResult(context:Context,pending:PendingRecording,raw:String,cleaned:String,accepted:Boolean){
        val entry=DictationEntry(id=pending.id,timestamp=pending.timestamp,appPackage=pending.appPackage,appLabel=pending.appLabel,durationSec=pending.durationSec,words=cleaned.trim().split(Regex("\\s+")).count{it.isNotBlank()},accepted=accepted,onDevice=pending.sttProvider=="local",before=raw,after=cleaned)
        DictationHistory.recordDurably(context,entry);PendingAudio.settle(context,pending.id,cleaned)
    }
    /** History retry never commits into an editor or starts a fresh recording. */
    suspend fun retryToHistory(context:Context,id:String):String{
        val app=context.applicationContext
        val record=PendingAudio.get(app,id) ?: throw IllegalStateException("Recording no longer exists")
        PendingAudio.claim(id)
        try{
            val result=process(app,record,null){}
            withContext(NonCancellable+Dispatchers.IO){saveResult(app,record,result.first,result.second,false)}
            return result.second
        }finally{PendingAudio.release(id)}
    }
    /** Explicit Cancel keeps the audio in history as "Cancelled" for 7 days. Lifecycle cancellation preserves it as an interrupted take. */
    @Synchronized
    fun cancel(discard:Boolean=true){
        discardActive.set(discard)
        operationToken++
        val capture=recorder;recorder=null
        val host=activeHost;val session=activeSession
        val selection=activeSelection
        // Do not stop a recorder while its asynchronous start is still constructing AudioRecord;
        // the start job owns cleanup in that race. For an already-running capture, do the same
        // stop and sample assembly off the IME thread.
        appContext?.let{DictationForegroundService.stop(it)}
        val cancelledTake=take
        if(capture!=null&&startJob?.isActive!=true){
            val app=host?.voiceContext?.applicationContext
            val duration=((System.currentTimeMillis()-startedAt)/1000).toInt().coerceAtLeast(1)
            val cleanup=scope.launch(Dispatchers.IO){
                val samples=withContext(NonCancellable+Dispatchers.IO){capture.stop()}
                if((!discard||(app!=null&&keepCancelled(app,duration)))&&!cancelledTake.incognito&&selection==null&&samples!=null&&app!=null&&host!=null&&session!=null)
                    preserveForRetry(app,samples,session,duration,cancelled=discard)
            }
            captureCleanup=cleanup
            cleanup.invokeOnCompletion{if(captureCleanup===cleanup)captureCleanup=null}
        }
        work?.cancel();activeHost?.setVoiceStatus(if(discard)"Cancelled" else "Ready");activeHost=null;activeSession=null;activeSelection=null
    }
}
