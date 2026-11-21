package dev.lemon.rex

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

internal data class ImageTarget(val server: String,val endpoint: String,val session: String,val block: String,
    val navigation: Long,val serverSwitch: Long,val program: ProgramKind,val label: String)
internal enum class ImagePhase { PREPARING, UPLOADING, FINISHING, SUCCESS, FAILED }
internal data class ImageUploadStatus(val target: ImageTarget,val phase: ImagePhase,
    val progress: Float=0f,val error: String="",val retrySafe: Boolean=true)
internal data class StagedImage(val file: File,val name: String)
internal const val MAX_IMAGE_BYTES=100L*1024*1024
internal val imageMimeTypes=arrayOf("image/png","image/jpeg","image/webp","image/gif")

internal fun imageExtension(header: ByteArray): String? = when {
    header.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10))->"png"
    header.size>=3 && header[0]==0xff.toByte() && header[1]==0xd8.toByte() && header[2]==0xff.toByte()->"jpg"
    header.size>=6 && String(header,0,6,Charsets.US_ASCII) in listOf("GIF87a","GIF89a")->"gif"
    header.size>=12 && String(header,0,4,Charsets.US_ASCII)=="RIFF" && String(header,8,4,Charsets.US_ASCII)=="WEBP"->"webp"
    else->null
}

/**
 * The name Rex creates the uploaded file under. Rex accepts any file; it keeps only the last path
 * component and strips control characters itself, but a readable fallback beats an empty name.
 */
internal fun uploadName(displayName: String?,header: ByteArray): String {
    val name=displayName.orEmpty().substringAfterLast('/').filter {it>=' ' && it!='\u007f'}.trim()
    if(name.isNotEmpty() && name!="." && name!="..") return name
    return imageExtension(header)?.let {"image.$it"} ?: "file"
}

internal class ImageUploads(private val context: Context,private val scope: CoroutineScope,
    private val native: RexUploadBackend=RexImageUpload()) {
    var state by mutableStateOf<ImageUploadStatus?>(null);private set
    var notice by mutableStateOf("")
    private var job: Job?=null
    private var staged: StagedImage?=null
    val retryable: Boolean get()=staged!=null && state?.retrySafe==true
    private var operation=0L
    private val directory=File(context.cacheDir,"image-uploads")
    private var preparing: File?=null
    fun start(uri: Uri,target: ImageTarget,onCopied: ()->Unit={}): Boolean {
        if(state?.phase==ImagePhase.SUCCESS) clear()
        if(state!=null) {onCopied();notice="Finish or cancel the current upload first.";return false}
        if(uri.scheme!="content") {onCopied();notice="Choose a file from your clipboard or file picker";return false}
        val released=java.util.concurrent.atomic.AtomicBoolean(false)
        val release={if(released.compareAndSet(false,true)) onCopied()}
        val file=File(directory,UUID.randomUUID().toString());preparing=file
        state=ImageUploadStatus(target,ImagePhase.PREPARING)
        val id=++operation
        job=scope.launch {
            try {
                staged=withContext(Dispatchers.IO) {stage(uri,file,release)}
                preparing=null
                if(id==operation) upload(id)
            } catch(error: CancellationException) {throw error}
            catch(error: Exception) {if(id==operation) state=state?.copy(phase=ImagePhase.FAILED,error=error.message ?: "Could not prepare this file")}
        }
        job?.invokeOnCompletion {release()}
        return true
    }
    private suspend fun stage(uri: Uri,file: File,onCopied: ()->Unit): StagedImage {
        try {
            directory.mkdirs()
            directory.listFiles()?.filter {it.lastModified()<System.currentTimeMillis()-24*60*60*1000L}?.forEach {it.delete()}
            require(uri.scheme=="content") {"Choose a file from your clipboard or file picker"}
            val input=context.contentResolver.openInputStream(uri) ?: error("This file is no longer available")
            input.use {source->file.outputStream().use {sink->
                val buffer=ByteArray(65536);var total=0L
                while(true) {currentCoroutineContext().ensureActive();val n=source.read(buffer);if(n<0) break;total+=n
                    require(total<=MAX_IMAGE_BYTES) {"Choose a file smaller than 100 MiB"};sink.write(buffer,0,n)}
            }}
            val header=file.inputStream().use {inputStream->val bytes=ByteArray(12);var n=0;while(n<12) {val count=inputStream.read(bytes,n,12-n);if(count<0) break;n+=count};bytes.copyOf(n)}
            val displayName=runCatching {
                context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                    if(it.moveToFirst()) it.getString(0) else null}
            }.getOrNull()
            return StagedImage(file,uploadName(displayName,header))
        } catch(error: Throwable) {file.delete();throw error}
        finally {onCopied()}
    }
    private suspend fun upload(id: Long) {
        val current=state ?: return
        val image=staged ?: error("Choose the file again to retry")
        state=current.copy(phase=ImagePhase.PREPARING,error="",progress=0f)
        val result=native.upload(current.target,image) {phase,progress->
            if(id==operation) state=state?.copy(phase=when(phase) {"preparing"->ImagePhase.PREPARING;"finishing"->ImagePhase.FINISHING;else->ImagePhase.UPLOADING},progress=progress)
        }
        if(id!=operation) return
        when(result.phase) {
            "done"->{
                state=state?.copy(phase=ImagePhase.SUCCESS,progress=1f)
                delay(750)
                if(id==operation) {job=null;clear()}
                return
            }
            "uncertain"->{state=state?.copy(phase=ImagePhase.FAILED,error=result.error,retrySafe=false);return}
            "failed"->{state=state?.copy(phase=ImagePhase.FAILED,error=result.error);return}
            "canceled"->return
            "unsupported"->{state=state?.copy(phase=ImagePhase.FAILED,error="Update the Rex server to enable file uploads (API revision 11 or newer).");return}
            else->error("Unexpected upload state")
        }
    }
    fun retry() {
        if(state?.phase!=ImagePhase.FAILED || !retryable) return
        if(staged==null) {notice="This file could not be prepared. Cancel and choose it again.";return}
        val id=++operation
        job=scope.launch {try {upload(id)} catch(error: CancellationException) {throw error} catch(error: Exception) {if(id==operation) state=state?.copy(phase=ImagePhase.FAILED,error=error.message ?: "Upload failed")}}
    }
    fun cancel() {
        if(state?.phase==ImagePhase.FINISHING) notice="Upload stopped while finishing. Check the original pane before trying again."
        clear()
    }
    private fun clear() {
        operation++;job?.cancel();job=null
        val files=listOfNotNull(preparing,staged?.file).distinct()
        preparing=null;staged=null;state=null
        if(files.isNotEmpty()) scope.launch(Dispatchers.IO+NonCancellable) {files.forEach {it.delete()}}
    }
    fun forget(serverId: String) {if(state?.target?.server==serverId) cancel()}
    fun close()=cancel()
}

internal fun pasteTerminalClipboard(context: Context,onImage: (Uri)->Boolean,onText: (String)->Unit): Boolean {
    val clip=(context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip ?: return false
    if(clip.itemCount==0) return false
    val item=clip.getItemAt(0);val uri=item.uri
    // A copied file (image or not) uploads; copied text, including text exposed as a URI, pastes.
    val type=uri?.let {runCatching {context.contentResolver.getType(it)}.getOrNull()}
    val file=uri!=null && uri.scheme=="content" && (clip.description.hasMimeType("image/*") || (type!=null && !type.startsWith("text/")))
    return if(file) onImage(uri!!)
        else {val text=item.coerceToText(context)?.toString().orEmpty();if(text.isEmpty()) false else {onText(text);true}}
}
