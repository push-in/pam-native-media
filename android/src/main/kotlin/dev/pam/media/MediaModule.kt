package dev.pam.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import kotlin.math.max

class MediaModule(context: Context) : NativeModule {
    private val root = context.applicationContext.filesDir.canonicalFile

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            when (method) {
                "probe" -> probe(file(values.text("path"), true))
                "thumbnail" -> thumbnail(values)
                else -> error("Unknown method: $method")
            }
        }.onSuccess { completion.success(it) }.onFailure { completion.failure(it) }
    }

    private fun probe(source: File): Map<String, WireValue> {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase()) ?: "application/octet-stream"
        var width = 0; var height = 0; var duration = 0L; var orientation = 0
        val kind = when {
            mime.startsWith("image/") -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, options); width = options.outWidth.coerceAtLeast(0); height = options.outHeight.coerceAtLeast(0)
                orientation = exifDegrees(source); if (orientation == 90 || orientation == 270) { val swap = width; width = height; height = swap }; 1
            }
            mime.startsWith("audio/") || mime.startsWith("video/") -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(source.path)
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    orientation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } finally {
                    retriever.release()
                }
                if (mime.startsWith("video/")) 3 else 2
            }
            else -> 4
        }
        return mapOf("kind" to WireValue.Integer(kind.toLong()), "mimeType" to WireValue.Text(mime), "bytes" to WireValue.Integer(source.length()), "width" to WireValue.Integer(width.toLong()), "height" to WireValue.Integer(height.toLong()), "durationMillis" to WireValue.Integer(duration), "orientationDegrees" to WireValue.Integer(orientation.toLong()))
    }

    private fun thumbnail(values: Map<String, WireValue>): Map<String, WireValue> {
        val source=file(values.text("source"),true);val destination=file(values.text("destination"),false);destination.parentFile?.mkdirs()
        val mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase()).orEmpty()
        val original=if(mime.startsWith("video/")){videoFrame(source,values.integer("timeMillis"))}else{decodeImage(source,values.integer("maxWidth").toInt(),values.integer("maxHeight").toInt())}
        val oriented=if(mime.startsWith("image/"))rotate(original,exifDegrees(source))else original
        val scale=minOf(values.integer("maxWidth").toFloat()/oriented.width,values.integer("maxHeight").toFloat()/oriented.height,1f)
        val resized=if(scale<1f)Bitmap.createScaledBitmap(oriented,max(1,(oriented.width*scale).toInt()),max(1,(oriented.height*scale).toInt()),true)else oriented
        destination.outputStream().use{out->val format=if(values.integer("format")==2L)Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG;check(resized.compress(format,values.integer("quality").toInt(),out)){"Thumbnail encoding failed"}}
        if(resized!==oriented)resized.recycle();if(oriented!==original)oriented.recycle();original.recycle()
        return mapOf("path" to WireValue.Text(values.text("destination")))
    }
    private fun decodeImage(source:File,maxWidth:Int,maxHeight:Int):Bitmap{val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(source.path,bounds);require(bounds.outWidth>0&&bounds.outHeight>0){"Unsupported image"};var sample=1;while(bounds.outWidth/sample>maxWidth*2||bounds.outHeight/sample>maxHeight*2)sample*=2;return BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Image decode failed")}
    private fun videoFrame(source:File,timeMillis:Long):Bitmap{val retriever=MediaMetadataRetriever();try{retriever.setDataSource(source.path);return retriever.getFrameAtTime(timeMillis*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?:error("Video frame unavailable")}finally{retriever.release()}}
    private fun rotate(bitmap:Bitmap,degrees:Int):Bitmap=if(degrees==0)bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply{postRotate(degrees.toFloat())},true)
    private fun exifDegrees(file:File)=when(ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)){ExifInterface.ORIENTATION_ROTATE_90->90;ExifInterface.ORIENTATION_ROTATE_180->180;ExifInterface.ORIENTATION_ROTATE_270->270;else->0}
    private fun file(path:String,mustExist:Boolean):File{val target=File(root,path).canonicalFile;require(target.path.startsWith(root.path+File.separator)){"Path escapes app files"};if(mustExist)require(target.isFile){"Media file does not exist"};return target}
    private fun Map<String,WireValue>.text(key:String)=(get(key)as?WireValue.Text)?.value?:error("$key is required")
    private fun Map<String,WireValue>.integer(key:String)=(get(key)as?WireValue.Integer)?.value?:error("$key is required")
    private fun ModuleCompletion.success(values:Map<String,WireValue>)=complete(ModuleResultStatus.SUCCESS,WireMap.encode(values))
    private fun ModuleCompletion.failure(error:Throwable)=complete(ModuleResultStatus.FAILURE,(error.message?:"Media failure").toByteArray())
}
