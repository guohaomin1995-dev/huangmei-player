package app.huangmei.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parseName() {
        assertEquals("天仙配" to "严凤英", VideoLibrary.parseName("01-天仙配-严凤英"))
        assertEquals("女驸马" to "", VideoLibrary.parseName("02.女驸马"))
        assertEquals("打猪草" to "", VideoLibrary.parseName("3 打猪草"))
        assertEquals("夫妻观灯" to "", VideoLibrary.parseName("04夫妻观灯"))
        assertEquals("孟丽君" to "吴琼 1990", VideoLibrary.parseName("孟丽君_吴琼 - 1990"))
        // 年份不当序号
        assertEquals("1958年天仙配" to "", VideoLibrary.parseName("1958年天仙配"))
        // 只有数字时保留原名
        assertEquals("123" to "", VideoLibrary.parseName("123"))
    }

    @Test
    fun parseBookTitleNames() {
        assertEquals("天仙配" to "严凤英", VideoLibrary.parseName("黄梅戏《天仙配》严凤英"))
        assertEquals("天仙配" to "", VideoLibrary.parseName("黄梅戏《天仙配》"))
        assertEquals("七仙女" to "电影", VideoLibrary.parseName("黄梅调电影《七仙女》"))
        assertEquals("啼笑因缘（上）" to "张弓 周莉 汪静", VideoLibrary.parseName("黄梅戏《啼笑因缘》张弓 周莉 汪静（上部）"))
        assertEquals("墨痕（下）" to "徐君 宁晓志 王丹红", VideoLibrary.parseName("黄梅戏《墨痕》徐君 宁晓志 王丹红（下部）"))
        assertEquals("牛郎织女" to "严凤英（电影）", VideoLibrary.parseName("黄梅戏《牛郎织女》严凤英（电影）"))
        assertEquals("秋千架" to "马兰 吴亚玲", VideoLibrary.parseName("黄梅戏《秋千架》马兰 吴亚玲 "))
        assertEquals("碧玉簪" to "汪莉 刘国平", VideoLibrary.parseName("黄梅戏《碧玉簪》 汪莉 刘国平"))
        assertEquals("天仙配" to "严凤英", VideoLibrary.parseName("01-黄梅戏《天仙配》严凤英"))
        assertEquals(1, VideoLibrary.leadingOrder("01-黄梅戏《天仙配》严凤英"))
        assertEquals(null, VideoLibrary.leadingOrder("黄梅戏《天仙配》严凤英"))
        assertEquals(null, VideoLibrary.leadingOrder("123"))
    }

    @Test
    fun scanSortsNaturallyAndFindsPosters() {
        val root = tmp.newFolder("黄梅戏")
        listOf("10-罗帕记.mp4", "2-女驸马.mkv", "1-天仙配.mp4", "._1-天仙配.mp4", "说明.txt").forEach {
            File(root, it).writeText("x")
        }
        File(root, "1-天仙配.JPG").writeText("x")
        File(root, ".hidden").mkdir()
        File(root, ".hidden/9-x.mp4").writeText("x")
        File(root, "选段").mkdir()
        File(root, "选段/1-树上的鸟儿成双对.mp4").writeText("x")

        val videos = VideoLibrary.scan(listOf(root))
        // 序号相同(1)时按戏名拼音：树上的鸟儿成双对(s) 在 天仙配(t) 前
        assertEquals(
            listOf("树上的鸟儿成双对", "天仙配", "女驸马", "罗帕记"),
            videos.map { it.title },
        )
        assertEquals("1-天仙配.jpg", videos[1].poster?.name?.lowercase())
        assertNull(videos[2].poster)
    }

    @Test
    fun scanMergesRootsAndSortsByNumberThenTitle() {
        val movies = tmp.newFolder("Movies")
        val download = tmp.newFolder("Download")
        File(download, "黄梅戏《女驸马》严凤英.rmvb").writeText("x")
        File(download, "黄梅调电影《七仙女》.rmvb").writeText("x")
        File(download, "黄梅戏《天仙配》.flv").writeText("x")
        File(movies, "02-打猪草.mp4").writeText("x")
        File(movies, "01-夫妻观灯.mp4").writeText("x")
        File(download, "说明.txt").writeText("x")

        val titles = VideoLibrary.scan(listOf(movies, download, File(tmp.root, "不存在"))).map { it.title }
        assertEquals(listOf("夫妻观灯", "打猪草", "女驸马", "七仙女", "天仙配"), titles)
    }
}
