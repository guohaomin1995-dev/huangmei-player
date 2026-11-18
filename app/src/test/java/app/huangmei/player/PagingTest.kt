package app.huangmei.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PagingTest {
    private fun v(name: String) = Video(File("/x/$name.mp4"), name, "", null)

    private val all = (1..14).map { v("戏$it") }

    @Test
    fun noFavoritesIsPlainPaging() {
        val l = Paging.layout(all, emptyList(), perPage = 6)
        assertEquals(0, l.favoritePages)
        assertEquals(listOf(6, 6, 2), l.pages.map { it.size })
        assertEquals(all, l.ordered)
    }

    @Test
    fun favoritesComeFirstInPickedOrderAndAreNotRepeated() {
        val favs = listOf(all[9].path, all[2].path, "/x/已删除.mp4", all[2].path)
        val l = Paging.layout(all, favs, perPage = 6)
        assertEquals(1, l.favoritePages)
        // 常看单独一页（不足 6 部也不和其余的混在一页）
        assertEquals(listOf("戏10", "戏3"), l.pages[0].map { it.title })
        assertEquals(listOf(6, 6), l.pages.drop(1).map { it.size })
        assertEquals(14, l.ordered.size)
        assertEquals(listOf("戏10", "戏3", "戏1", "戏2", "戏4"), l.ordered.take(5).map { it.title })
        assertEquals(0, l.pageOf(all[2].path))
        assertEquals(1, l.pageOf(all[0].path))
        assertEquals(null, l.pageOf("/x/没有.mp4"))
    }

    @Test
    fun atMostTwelveFavoritesOverTwoPages() {
        val l = Paging.layout(all, all.map { it.path }, perPage = 6)
        assertEquals(2, l.favoritePages)
        assertEquals(listOf(6, 6, 2), l.pages.map { it.size })
    }
}
