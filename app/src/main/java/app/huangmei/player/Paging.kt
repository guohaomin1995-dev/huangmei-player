package app.huangmei.player

/**
 * 海报墙怎么分页：家人挑的"常看"排在最前（按挑选顺序，位置固定），从新的一页开始再排其余的戏。
 * 常看的戏不在后面重复出现。
 */
object Paging {
    const val MAX_FAVORITES = 12

    data class Layout(
        /** 每页最多 PER_PAGE 部。 */
        val pages: List<List<Video>>,
        /** 前几页是常看（0 表示没有常看）。 */
        val favoritePages: Int,
    ) {
        /** 播放顺序：常看在前，其余在后（自动下一部也按这个顺序）。 */
        val ordered: List<Video> get() = pages.flatten()

        fun pageOf(path: String?): Int? =
            path?.let { p -> pages.indexOfFirst { page -> page.any { it.path == p } }.takeIf { it >= 0 } }
    }

    fun layout(videos: List<Video>, favorites: List<String>, perPage: Int = PER_PAGE): Layout {
        val byPath = videos.associateBy { it.path }
        val favs = favorites.distinct().mapNotNull { byPath[it] }.take(MAX_FAVORITES)
        val favPaths = favs.map { it.path }.toSet()
        val rest = videos.filter { it.path !in favPaths }
        val favPages = favs.chunked(perPage)
        return Layout(favPages + rest.chunked(perPage), favPages.size)
    }

    /** 挑选常看时：按原来的顺序全部列出，被选中的位置不跳来跳去。 */
    fun plain(videos: List<Video>, perPage: Int = PER_PAGE): Layout = Layout(videos.chunked(perPage), 0)
}
