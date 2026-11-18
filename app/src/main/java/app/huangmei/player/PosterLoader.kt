package app.huangmei.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/** 海报：优先用同名图片，没有就从视频第 10%（最多 5 分钟处）截一帧并缓存到磁盘。 */
class PosterLoader(context: Context) {
    private val memory = LruCache<String, ImageBitmap>(30)
    private val gate = Semaphore(2)
    private val thumbDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    /** 截不出帧的（比如 RMVB），记下来别每次翻页都重试。 */
    private val failed = Collections.synchronizedSet(HashSet<String>())

    fun cached(video: Video): ImageBitmap? = memory.get(video.posterKey)

    suspend fun load(video: Video): ImageBitmap? {
        memory.get(video.posterKey)?.let { return it }
        if (video.posterKey in failed) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                val bitmap = video.poster?.let { decode(it) } ?: frame(video.file)
                if (bitmap == null) failed.add(video.posterKey)
                bitmap?.asImageBitmap()?.also { memory.put(video.posterKey, it) }
            }
        }
    }

    private fun frame(file: File): Bitmap? {
        val cacheFile = File(thumbDir, sha1("${file.absolutePath}|${file.length()}|${file.lastModified()}") + ".jpg")
        if (cacheFile.isFile) decode(cacheFile)?.let { return it }

        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = (if (durationMs > 0) minOf(durationMs / 10, 300_000L) else 60_000L) * 1000
            val bitmap = if (Build.VERSION.SDK_INT >= 27) {
                mmr.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET, TARGET)
            } else {
                mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            bitmap?.also { b -> cacheFile.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        } catch (e: Exception) {
            Log.w(TAG, "截帧失败 ${file.name}: ${e.message}")
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PosterLoader"
        const val TARGET = 640
    }
}
