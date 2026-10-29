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
    private val accent=android.graphics.Color.rgb(138,180,248)
    private val cardColor=android.graphics.Color.rgb(58,58,62)
    private fun round(color:Int,r:Int=14)=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=dp(r).toFloat()}
    private fun pill(text:String,primary:Boolean=false,action:()->Unit)=Button(this).apply{
        this.text=text;isAllCaps=false;textSize=13f;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
        setPadding(dp(16),0,dp(16),0)
        if(primary){setTextColor(android.graphics.Color.rgb(20,24,36));background=round(accent,20)}
        else{setTextColor(android.graphics.Color.WHITE);background=round(0xFF4A4A50.toInt(),20)}
        stateListAnimator=null
        setOnClickListener{action()}
        layoutParams=LinearLayout.LayoutParams(-2,dp(38)).apply{rightMargin=dp(8)}
    }
    private fun badge(text:String,color:Int)=TextView(this).apply{
        this.text=text.uppercase();textSize=10f;setTextColor(color);letterSpacing=0.08f
        setPadding(dp(8),dp(2),dp(8),dp(2));background=round((color and 0xFFFFFF) or 0x33000000,8)
    }
    private fun card(parent:LinearLayout):LinearLayout{
        val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));background=round(cardColor)}
        parent.addView(c,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)});return c
    }
    private fun sectionTitle(parent:LinearLayout,text:String){
        parent.addView(label(text.uppercase(),11f,muted).apply{letterSpacing=0.1f;setPadding(dp(4),dp(14),0,dp(6))})
    }
    private fun dayLabel(ts:Long):String{
        val flags=android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY
        return when{
            android.text.format.DateUtils.isToday(ts)->"Today"
            android.text.format.DateUtils.isToday(ts+86_400_000L)->"Yesterday"
            else->android.text.format.DateUtils.formatDateTime(this,ts,flags)
        }
    }
    private fun clock(ts:Long)=android.text.format.DateUtils.formatDateTime(this,ts,android.text.format.DateUtils.FORMAT_SHOW_TIME)
    private fun duration(sec:Int)=if(sec<60)"${sec}s" else "${sec/60}m ${sec%60}s"
    private fun render(){
        loadJob?.cancel()
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(24))}
        val scroll=ScrollView(this).apply{setBackgroundColor(this@VoiceHistoryActivity.background);clipToPadding=false;addView(content)}
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        setContentView(scroll)
        androidx.core.view.ViewCompat.requestApplyInsets(scroll)
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(4),0,0,dp(8))}
        header.addView(label("History",26f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(pill("Refresh"){render()});header.addView(pill("Settings"){settingsDialog()})
        content.addView(header)
        val search=EditText(this).apply{
            hint="Search dictations";setHintTextColor(muted);setTextColor(android.graphics.Color.WHITE);textSize=15f;setSingleLine(true)
            background=round(cardColor,22);setPadding(dp(18),0,dp(18),0)
        }
        content.addView(search,LinearLayout.LayoutParams(-1,dp(46)).apply{bottomMargin=dp(6)})
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};content.addView(list)
        loadJob=scope.launch{
            val pending=withContext(Dispatchers.IO){PendingAudio.unfinished(applicationContext)}
            val entries=withContext(Dispatchers.IO){DictationHistory.all(applicationContext)}
            fun fill(query:String){
                list.removeAllViews()
                val q=query.trim().lowercase()
                val rows=if(q.isEmpty())entries else entries.filter{it.after.lowercase().contains(q)||it.appLabel.lowercase().contains(q)}
                if(rows.isEmpty()&&(q.isNotEmpty()||pending.isEmpty())){
                    list.addView(label(if(q.isEmpty())"Your dictations will appear here." else "No matches.",17f,muted).apply{setPadding(0,dp(44),0,dp(44));gravity=android.view.Gravity.CENTER})
                }
                // One timeline: saved recordings sit in line with finished dictations by time.
                val timeline=(rows.take(visibleCount).map{it.timestamp to (it as Any)}+(if(q.isEmpty())pending.map{it.timestamp to (it as Any)} else emptyList())).sortedByDescending{it.first}
                var lastDay=""
                timeline.forEach{(ts,item)->
                    val day=dayLabel(ts)
                    if(day!=lastDay){sectionTitle(list,day);lastDay=day}
                    if(item is PendingRecording){pendingCard(list,item);return@forEach}
                    val entry=item as DictationEntry
                    val c=card(list)
                    c.addView(label("${clock(entry.timestamp)} · ${entry.appLabel}",12f,muted).apply{setPadding(0,0,0,dp(4))})
                    c.addView(label(entry.after,17f).apply{setLineSpacing(dp(3).toFloat(),1f);setPadding(0,dp(2),0,dp(8))})
                    val actions=LinearLayout(this@VoiceHistoryActivity).apply{orientation=LinearLayout.HORIZONTAL}
                    actions.addView(pill("Copy"){copy(entry.after)})
                    actions.addView(pill("Delete"){confirmDelete("Delete this dictation?"){DictationHistory.delete(applicationContext,entry.id);PendingAudio.discard(applicationContext,entry.id);render()}})
                    c.addView(actions)
                }
                if(rows.size>visibleCount)list.addView(pill("Load older",false){visibleCount+=100;render()})
            }
            fill("")
            search.addTextChangedListener(object:android.text.TextWatcher{
                override fun afterTextChanged(s:android.text.Editable?){fill(s?.toString().orEmpty())}
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
            })
        }
    }
    private fun confirmDelete(message:String,action:()->Unit){
        AlertDialog.Builder(this,android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK).setMessage(message)
            .setNegativeButton("Keep",null).setPositiveButton("Delete"){_,_->action()}.show()
    }
    private fun pendingCard(parent:LinearLayout,record:PendingRecording){
        val c=card(parent)
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
        top.addView(badge(if(record.cancelled)"Cancelled" else "Interrupted",if(record.cancelled)0xFFF2B35E.toInt() else 0xFFE87C7C.toInt()))
        top.addView(label("  ${clock(record.timestamp)} · ${duration(record.durationSec)}",12f,muted).apply{setPadding(dp(6),0,0,0)})
        c.addView(top)
        val state=label(if(record.cancelled)"You cancelled this. The audio is saved on this device for ${PendingAudio.CANCELLED_KEEP_DAYS} days." else "Your audio is saved on this device.",14f,muted).apply{setPadding(0,dp(8),0,dp(8))}
        c.addView(state)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        lateinit var retry:Button
        retry=pill("Transcribe",true){
            retry.isEnabled=false;state.text="Transcribing saved recording…"
            retryJobs[record.id]=scope.launch{
                try{
                    val result=ImeDictationPipeline.retryToHistory(applicationContext,record.id)
                    state.text=result;state.setTextColor(android.graphics.Color.WHITE);state.textSize=17f
                    retry.text="Copy";retry.isEnabled=true;retry.setOnClickListener{copy(result)}
                }
                catch(e:CancellationException){throw e}
                catch(e:Exception){state.text="Could not transcribe. Your audio is still saved. ${e.message.orEmpty().take(100)}";retry.isEnabled=true}
                finally{retryJobs.remove(record.id)}
            }
        }
        retry.isEnabled=retryJobs[record.id]?.isActive!=true
        actions.addView(retry)
        actions.addView(pill("Delete"){confirmDelete("Delete this recording? The audio cannot be recovered."){PendingAudio.discard(applicationContext,record.id);render()}})
        c.addView(actions)
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
