package srtm

import wasichai.core.common.PageResponse

// a page of rows already in memory, filtered by what core cannot filter (an estado or a fase that derive from other
// rows): past the last row it is empty, as core's pages are, whatever `page` is. page × size is counted in Long: an
// absurd page is an empty answer, not an overflow and a 500
fun <T> List<T>.pagina(
    page: Int,
    size: Int
): PageResponse<T> {
    val desde = page.toLong() * size
    val filas = if (desde >= this.size) emptyList() else drop(desde.toInt()).take(size)
    return PageResponse.of(filas, page, size, this.size.toLong())
}
