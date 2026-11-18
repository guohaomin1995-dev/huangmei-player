package app.huangmei.player

import android.os.Environment
import java.io.File
import java.text.Collator
import java.util.Locale

data class Video(
    val file: File,
    val title: String,
    val subtitle: String,
    val poster: File?,
    /** 文件名开头的序号，没有就排在最后。 */
    val order: Int = Int.MAX_VALUE,
) {
    val path: String get() = file.absolutePath

    /** 海报换了（新放了 jpg）要重新加载，所以把海报文件也算进缓存键。 */
    val posterKey: String get() = "$path|${poster?.path}|${poster?.lastModified()}"
}

/**
 * 约定：视频放在 内部存储/Movies/黄梅戏/ 下（可以有子文件夹），
 * 文件名 "01-天仙配-严凤英.mp4"：序号决定顺序，第一段是戏名，后面是副标题；
 * 同名 .jpg/.png/.webp 当海报，没有就从视频里截一帧。
 */
object VideoLibrary {
    const val FOLDER = "黄梅戏"

    private val videoExt = setOf(
        "mp4", "m4v", "mkv", "mov", "webm", "3gp", "ts", "avi", "flv", "f4v", "mpg", "mpeg",
        // RMVB 要 VLC 内核才能放；有的 .rmvb/.flv 其实是改了后缀的 MP4，现在的播放器按内容识别也能放
        "rmvb", "rm",
    )
    private val imageExt = listOf("jpg", "jpeg", "png", "webp")

    /** 依次扫描：内部存储/Movies/黄梅戏、内部存储/Download/黄梅戏（都可以有子文件夹）。 */
    fun rootDirs(): List<File> =
        listOf(Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DOWNLOADS)
            .map { File(Environment.getExternalStoragePublicDirectory(it), FOLDER) }

    /** 排序：文件名带序号的按序号在前；其余按戏名拼音，再按副标题。 */
    private val order = compareBy<Video> { it.order }
        .thenBy(NaturalOrder) { it.title }
        .thenBy(NaturalOrder) { it.subtitle }
        .thenBy { it.path }

    fun scan(roots: List<File> = rootDirs()): List<Video> =
        roots.filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .onEnter { it == root || !it.name.startsWith(".") }
                    // "._xxx.mp4" 是 Mac 拷文件时留下的垃圾文件
                    .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in videoExt }
                    .toList()
            }
            .map(::toVideo)
            .sortedWith(order)

    private fun toVideo(file: File): Video {
        val name = file.nameWithoutExtension
        val (title, subtitle) = parseName(name)
        return Video(file, title, subtitle, findPoster(file), leadingOrder(name) ?: Int.MAX_VALUE)
    }

    private fun findPoster(video: File): File? {
        val dir = video.parentFile ?: return null
        val base = video.nameWithoutExtension
        return imageExt.flatMap { listOf(it, it.uppercase()) }
            .map { File(dir, "$base.$it") }
            .firstOrNull { it.isFile }
    }

    // 开头 1~3 位序号："01-"、"01."、"01 "、"01天仙配"；不吃掉 "1958年…" 这种年份
    private val leadingNumber = Regex("""^\s*(\d{1,3})(?:\s*[-_.、．]\s*|\s+|(?=\D))""")
    private val separators = Regex("""\s*[-_—－|｜]+\s*""")
    private val bookTitle = Regex("""《([^》]+)》""")
    private val genrePrefix = Regex("""^(黄梅戏|黄梅调)""")
    // "（上部）"、"(下)"、"上集"、结尾的"下部"
    private val partMarker = Regex("""[（(]\s*([上中下])\s*[部集]?\s*[）)]|([上中下])[部集]\s*$""")
    private val spaces = Regex("""\s+""")

    fun leadingOrder(name: String): Int? =
        leadingNumber.find(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { name.replaceFirst(leadingNumber, "").isNotBlank() }

    /**
     * "01-天仙配-严凤英" → 天仙配 / 严凤英
     * "黄梅戏《啼笑因缘》张弓 周莉（上部）" → 啼笑因缘（上） / 张弓 周莉
     * "黄梅调电影《七仙女》" → 七仙女 / 电影
     */
    fun parseName(name: String): Pair<String, String> {
        val stripped = name.replaceFirst(leadingNumber, "").ifBlank { name }

        bookTitle.find(stripped)?.let { m ->
            var title = m.groupValues[1].trim()
            val before = stripped.substring(0, m.range.first).replaceFirst(genrePrefix, "").trim(' ', '-', '_', '·')
            var after = stripped.substring(m.range.last + 1).trim()
            partMarker.find(after)?.let { p ->
                val part = p.groups[1]?.value ?: p.groups[2]?.value
                title = "$title（$part）"
                after = after.removeRange(p.range).trim()
            }
            val subtitle = listOf(before, after).filter { it.isNotBlank() }.joinToString(" ").replace(spaces, " ").trim()
            return title to subtitle
        }

        val parts = stripped.split(separators).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return name to ""
        return parts.first() to parts.drop(1).joinToString(" ")
    }
}

/** "2-xx" 排在 "10-xx" 前面；中文按拼音排。 */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""\d+|\D+""")
    private val collator: Collator = Collator.getInstance(Locale.CHINA)

    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a).map { it.value }.toList()
        val cb = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
            } else {
                collator.compare(x, y)
            }
            if (r != 0) return r
        }
        return ca.size.compareTo(cb.size)
    }
}
