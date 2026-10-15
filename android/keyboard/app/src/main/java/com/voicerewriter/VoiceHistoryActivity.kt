package com.voicerewriter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.widget.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

/** Private local history. Retry saves a result here and never writes into another app. */
class VoiceHistoryActivity: Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var loadJob:Job?=null
    private val retryJobs=mutableMapOf<String,Job>()
    private var visibleCount=100
    private val background=android.graphics.Color.rgb(45,45,48)
    private val muted=android.graphics.Color.rgb(183,183,190)
    private lateinit var content:LinearLayout
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(text:String,size:Float=16f,color:Int=android.graphics.Color.WHITE):TextView=TextView(this).apply{this.text=text;textSize=size;setTextColor(color);setPadding(0,dp(6),0,dp(6));setTextIsSelectable(true)}
    private fun button(text:String,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;textSize=14f;setTextColor(android.graphics.Color.WHITE);setBackgroundColor(android.graphics.Color.TRANSPARENT);minWidth=0;minimumWidth=0;setPadding(dp(12),0,dp(12),0);setOnClickListener{action()};layoutParams=LinearLayout.LayoutParams(-2,dp(48))}
    private fun separator(parent:LinearLayout){parent.addView(android.view.View(this).apply{setBackgroundColor(0xFF48484E.toInt())},LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(14);bottomMargin=dp(14)})}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false);window.statusBarColor=background;window.navigationBarColor=background;}
    override fun onResume(){super.onResume();render()}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    private fun copy(text:String){(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Dictation",text));Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show()}
    private fun render(){
        loadJob?.cancel()
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(18),dp(24),dp(24))}
        val scroll=ScrollView(this).apply{setBackgroundColor(this@VoiceHistoryActivity.background);clipToPadding=false;addView(content)}
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        setContentView(scroll)
        androidx.core.view.ViewCompat.requestApplyInsets(scroll)
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
        header.addView(label("OpenWhispr",25f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("Settings"){settingsDialog()});content.addView(header)
        val subhead=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
        subhead.addView(label("History",18f,muted),LinearLayout.LayoutParams(0,-2,1f))
        subhead.addView(button("Refresh"){render()});content.addView(subhead)
        val page=content
        loadJob=scope.launch{
            val pending=withContext(Dispatchers.IO){PendingAudio.unfinished(applicationContext)}
            pending.forEach{record->
                val group=LinearLayout(this@VoiceHistoryActivity).apply{orientation=LinearLayout.VERTICAL}
                group.addView(label("Recording ready to retry",17f))
                group.addView(label("${DateFormat.getDateTimeInstance().format(Date(record.timestamp))} · ${record.durationSec}s",13f,muted))
                val state=label("Your audio is saved on this device.",15f,muted);group.addView(state)
                val retry=button("Retry"){}
                retry.isEnabled=retryJobs[record.id]?.isActive!=true
                retry.setOnClickListener{
                    retry.isEnabled=false;state.text="Transcribing saved recording…"
                    retryJobs[record.id]=scope.launch{
                        try{val result=ImeDictationPipeline.retryToHistory(applicationContext,record.id);state.text=result;state.setTextColor(android.graphics.Color.WHITE);retry.text="Copy";retry.isEnabled=true;retry.setOnClickListener{copy(result)}}
                        catch(e:CancellationException){throw e}
                        catch(e:Exception){state.text="Could not transcribe. Your audio is still saved. ${e.message.orEmpty().take(100)}";retry.isEnabled=true}
                        finally{retryJobs.remove(record.id)}
                    }
                }
                group.addView(retry);page.addView(group);separator(page)
            }
            val entries=withContext(Dispatchers.IO){DictationHistory.all(applicationContext)}
            if(entries.isEmpty()&&pending.isEmpty())page.addView(label("Your dictations will appear here.",17f,muted).apply{setPadding(0,dp(44),0,dp(44))})
            entries.take(visibleCount).forEach{entry->
                page.addView(label("${DateFormat.getDateTimeInstance().format(Date(entry.timestamp))} · ${entry.appLabel}",13f,muted))
                page.addView(label(entry.after,18f).apply{setLineSpacing(dp(3).toFloat(),1f)})
                page.addView(button("Copy"){copy(entry.after)});separator(page)
            }
            if(entries.size>visibleCount)page.addView(button("Load older"){visibleCount+=100;render()})
        }
    }
    private fun settingsDialog(){scope.launch{
        val repo=SettingsRepository(applicationContext);val existing=repo.get()
        val form=LinearLayout(this@VoiceHistoryActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8));setBackgroundColor(this@VoiceHistoryActivity.background)}
        val ready=if(existing.sttModelResolved==ParakeetModelManager.MODEL_ID)ParakeetModelManager.isReady(applicationContext) else WhisperModelManager.isReady(applicationContext,existing.sttModelResolved)
        val speechStatus=if(existing.sttProvider=="local")"Local model: ${if(ready)"ready" else "not downloaded"}" else "Speech endpoint: ${existing.sttEndpointResolved}"
        form.addView(label("Speech: ${existing.sttModelResolved}\n$speechStatus\nCleanup: ${existing.model}",14f,muted))
        form.addView(button("Keyboard settings"){
            startActivity(android.content.Intent().setClassName(packageName,"helium314.keyboard.settings.SettingsActivity"))
        })
        fun field(title:String,value:String,secret:Boolean=false):EditText{
            form.addView(label(title))
            return EditText(this@VoiceHistoryActivity).apply{textSize=15f;setTextColor(android.graphics.Color.WHITE);setHintTextColor(muted);setSingleLine(true);if(secret){inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;hint="Leave blank to keep saved key"}else setText(value);form.addView(this)}
        }
        val provider=field("Cleanup provider",existing.provider)
        val model=field("Cleanup model",existing.model)
        val endpoint=field("Custom cleanup endpoint",existing.customEndpoint)
        val key=field("Cleanup API key","",true)
        val speechProvider=field("Speech provider: local or custom",existing.sttProvider)
        val speechModel=field("Speech model",existing.sttModelResolved)
        val speechEndpoint=field("Custom speech endpoint",existing.sttEndpoint)
        val speechKey=field("Speech API key","",true)
        val cleanup=CheckBox(this@VoiceHistoryActivity).apply{text="Use cleanup";setTextColor(android.graphics.Color.WHITE);isChecked=existing.llmPolishEnabled;form.addView(this)}
        form.addView(button("Download selected model"){
            val id=speechModel.text.toString().trim()
            if(id==ParakeetModelManager.MODEL_ID){scope.launch{runCatching{ParakeetModelManager.download(applicationContext){}}.onSuccess{Toast.makeText(this@VoiceHistoryActivity,"Model ready",Toast.LENGTH_SHORT).show()}.onFailure{Toast.makeText(this@VoiceHistoryActivity,"Download failed. Try again.",Toast.LENGTH_LONG).show()}}}
            else if(WhisperModelManager.MODELS.any{it.id==id}){WhisperModelManager.ensureDownloading(applicationContext,id);Toast.makeText(this@VoiceHistoryActivity,"Download started. Refresh history to check readiness.",Toast.LENGTH_LONG).show()}
        })
        AlertDialog.Builder(this@VoiceHistoryActivity,android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Voice settings").setView(ScrollView(this@VoiceHistoryActivity).apply{addView(form)}).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->
            scope.launch{
                val id=speechModel.text.toString().trim()
                val speech=speechProvider.text.toString().trim()
                if(speech=="local" && id!=ParakeetModelManager.MODEL_ID && WhisperModelManager.MODELS.none{it.id==id}){Toast.makeText(this@VoiceHistoryActivity,"Choose parakeet, tiny, base or small",Toast.LENGTH_LONG).show();return@launch}
                if(speech !in listOf("local","custom") || (speech=="custom" && (!speechEndpoint.text.toString().startsWith("https://") || id.isBlank()))){Toast.makeText(this@VoiceHistoryActivity,"Use local or a custom HTTPS speech endpoint",Toast.LENGTH_LONG).show();return@launch}
                repo.save(existing.copy(provider=provider.text.toString().trim(),model=model.text.toString().trim(),customEndpoint=endpoint.text.toString().trim(),apiKey=key.text.toString().ifBlank{existing.apiKey},sttProvider=speech,sttModel=id,sttEndpoint=speechEndpoint.text.toString().trim(),sttKey=speechKey.text.toString().ifBlank{existing.sttKey},polishLevel=if(cleanup.isChecked)PolishLevel.MEDIUM else PolishLevel.OFF))
                render()
            }
        }.show().apply{getButton(AlertDialog.BUTTON_POSITIVE).isAllCaps=false;getButton(AlertDialog.BUTTON_NEGATIVE).isAllCaps=false}
    }}
}
