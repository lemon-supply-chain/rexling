package dev.shulhi.rex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.UUID

internal data class RexUploadResult(val phase: String,val error: String)
internal fun interface RexUploadBackend {
    suspend fun upload(target: ImageTarget,image: StagedImage,onProgress: (String,Float)->Unit): RexUploadResult
}
internal class RexImageUpload: RexUploadBackend {
    override suspend fun upload(target: ImageTarget,image: StagedImage,onProgress: (String,Float)->Unit): RexUploadResult {
        val request=JSONObject().put("endpoint",target.endpoint).put("session",target.session).put("block",target.block)
            .put("path",image.file.absolutePath).put("name",image.name).put("ref","upload:${UUID.randomUUID()}")
        val handle=try {
            NativeBridge.uploadCreate(request.toString()).also {check(it!=0L) {"Could not start image upload"}}
        } catch(error: Throwable) {
            return RexUploadResult("failed",error.message ?: "Could not start image upload")
        }
        try {
            while(true) {
                currentCoroutineContext().ensureActive()
                val status=JSONObject(NativeBridge.uploadPoll(handle));val phase=status.getString("phase")
                if(phase in listOf("done","unsupported","failed","uncertain","canceled")) return RexUploadResult(phase,status.optString("error"))
                val total=status.optLong("total");val sent=status.optLong("sent")
                onProgress(phase,if(total>0) (sent.toDouble()/total).toFloat().coerceIn(0f,1f) else 0f)
                delay(40)
            }
        } catch(error: CancellationException) {throw error}
        catch(error: Exception) {return RexUploadResult("uncertain","Could not confirm the image upload. Check the original pane before trying again.")}
        finally {NativeBridge.uploadDestroy(handle)}
    }
}
