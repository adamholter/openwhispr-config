package com.voicerewriter

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import kotlinx.coroutines.*

/** Standard Android selection-menu entry. Only RESULT_OK can replace the caller's selection. */
class SelectionVoiceEditActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var recorder:AudioRecorder?=null
    private var work:Job?=null
    private var samples:ShortArray?=null
    private var selected=""
    private var result:String?=null
    private var readOnly=true
    private var foreground=false
    private lateinit var status:TextView
    private lateinit var preview:TextView
    private lateinit var speak:Button
    private lateinit var apply:Button
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        selected=intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        readOnly=intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true)
        if(intent.action!=Intent.ACTION_PROCESS_TEXT||selected.isBlank()||selected.length>12000){finish();return}
        window.statusBarColor=0xff2d2d30.toInt();window.navigationBarColor=0xff2d2d30.toInt()
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(24));setBackgroundColor(0xff2d2d30.toInt())}
        fun label(text:String,size:Float)=TextView(this).apply{this.text=text;textSize=size;setTextColor(0xffeeeeee.toInt());setPadding(0,dp(8),0,dp(8));column.addView(this)}
        fun button(text:String,click:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;setOnClickListener{click()};column.addView(this,LinearLayout.LayoutParams(-1,dp(52)))}
        label("Edit with voice",24f)
        status=label("Say how to change the selected text.",16f)
        preview=label(selected,18f).apply{setTextIsSelectable(true)}
        speak=button("Tap to speak"){if(recorder!=null)stopAndEdit() else startCapture()}
        button("Retry"){samples?.let{edit(it)}}
        apply=button(if(readOnly)"Copy result" else "Apply edit"){
            val text=result ?: return@button
            if(readOnly){(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Edited text",text));Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show()}
            else setResult(RESULT_OK,Intent().putExtra(Intent.EXTRA_PROCESS_TEXT,text))
            finish()
        }.apply{isEnabled=false}
        button("Cancel"){finish()}
        val scroll=ScrollView(this).apply{addView(column)}
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        setContentView(scroll);androidx.core.view.ViewCompat.requestApplyInsets(scroll)
    }
    private fun startCapture(){
        if(work?.isActive==true)return
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),1);return}
        work=scope.launch{
            try{
                val next=AudioRecorder(applicationContext)
                withContext(NonCancellable+Dispatchers.IO){next.start(vadAutoStop=false)}
                if(!isActive || !foreground){withContext(NonCancellable+Dispatchers.IO){next.stop()};return@launch}
                recorder=next;speak.text="Stop recording";status.text="Listening";apply.isEnabled=false
            }catch(e:Exception){status.text="Microphone unavailable. Original kept."}
        }
    }
    private fun stopAndEdit(){
        val active=recorder ?: return;recorder=null
        work=scope.launch{
            speak.isEnabled=false;status.text="Transcribing"
            val audio=withContext(Dispatchers.IO){active.stop()}
            samples=audio
            if(audio==null){status.text="No speech captured";speak.isEnabled=true;speak.text="Tap to speak"}
            else { work=null;edit(audio) }
        }
    }
    private fun edit(audio:ShortArray){
        if(work?.isActive==true||recorder!=null)return
        work=scope.launch{
            speak.isEnabled=false;apply.isEnabled=false
            try{
                result=ImeDictationPipeline.editSelectionAudio(applicationContext,audio,selected,callingPackage.orEmpty()){status.text=it}
                preview.text=result;status.text="Review your edit";apply.isEnabled=true
            }catch(e:CancellationException){throw e}
            catch(e:Exception){status.text="Edit failed. Original kept. Tap Retry."}
            finally{speak.isEnabled=true;speak.text="Tap to speak"}
        }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray){super.onRequestPermissionsResult(code,permissions,grants);if(code==1&&grants.firstOrNull()==PackageManager.PERMISSION_GRANTED)startCapture()}
    override fun onStart(){super.onStart();foreground=true}
    override fun onStop(){super.onStop();foreground=false;val active=recorder;recorder=null;if(active!=null){Thread{active.stop()}.start();speak.text="Tap to speak";status.text="Recording canceled. Original kept."}}
    override fun onDestroy(){scope.cancel();recorder?.let{active->Thread{active.stop()}.start()};recorder=null;super.onDestroy()}
}
