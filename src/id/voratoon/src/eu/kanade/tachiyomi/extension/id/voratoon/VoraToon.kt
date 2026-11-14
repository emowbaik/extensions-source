package eu.kanade.tachiyomi.extension.id.voratoon

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getTurnstileToken
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TURNSTILE_SITE_KEY = "0x4AAAAAAEOmIHqZQLQ6GPRc"
private const val TURNSTILE_ACTION = "reader"

/** Pages ticket is shipped in the RSC payload as a text chunk, e.g. `2d:T27fb,p1.<ticket>`. */
private val TICKET_REGEX = Regex("""T([0-9a-f]{3,6}),\s*(p1\.[A-Za-z0-9_.\-]+)""")

/** Reader entry chunk, whose hash rotates whenever the site is redeployed. */
private val READER_CHUNK_REGEX = Regex("""/_next/static/chunks/app/series/[^"'\s]*?/page-[0-9a-f]+\.js""")

/** Server action reference, e.g. `createServerReference("<id>",...,"unlockChapterPages")`. */
private val ACTION_ID_REGEX = Regex("""createServerReference\("([0-9a-f]{40,})"[^)]*?unlockChapterPages""")

private val IMAGE_REGEX = Regex("""https://cdn\.voratoon\.com/[^"\\\s]+""")
private val REASON_REGEX = Regex("""\\?"reason\\?":\\?"([a-z_]+)\\?""")

private class UnlockResult(val reason: String?, val images: List<String>)

/** Reads the ticket from the flight stream, using its own length prefix as the exact bound. */
private fun extractPagesTicket(payload: String): String? = TICKET_REGEX.findAll(payload)
    .mapNotNull { match ->
        val length = match.groupValues[1].toInt(16)
        match.groupValues[2].takeIf { it.length >= length }?.take(length)
    }
    .maxByOrNull { it.length }

@Source
abstract class VoraToon : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3)
    }

    private val rscHeaders: Headers
        get() = headers.newBuilder().set("RSC", "1").build()

    override suspend fun getPopularManga(page: Int): MangasPage = parseSeriesList(
        "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "popular")
            .build(),
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseSeriesList(
        "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "new")
            .build(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) {
                addQueryParameter("q", query)
            }
            filters.filterIsInstance<UriQueryFilter>().forEach {
                it.addToQuery(this)
            }
        }.build()

        return parseSeriesList(url)
    }

    private suspend fun parseSeriesList(url: HttpUrl): MangasPage {
        val dto = client.get(url, rscHeaders, Fetch.XHR).extractNextJs<BrowseDto> {
            it is JsonObject && "series" in it && "seriesMeta" in it
        } ?: return MangasPage(emptyList(), false)

        val mangas = dto.series.map { it.toSManga() }
        val hasNextPage = dto.seriesMeta?.let { (it.page ?: 0) < (it.lastPage ?: 0) } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val slug = manga.getSlug(baseUrl)
        val dto = client.get("$baseUrl/series/$slug", rscHeaders, Fetch.XHR).extractNextJs<SeriesDetailsDto> {
            it is JsonObject && "series" in it && "chapters" in it
        } ?: return SMangaUpdate(manga, chapters)

        val updatedManga = if (fetchDetails) {
            dto.series.firstOrNull()?.toSManga() ?: manga
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            dto.chapters.map { it.toSChapter(slug) }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.getOrNull(0) != "series") return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply {
            this.url = "/series/$slug"
        }
        return getMangaUpdate(
            manga,
            emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    private var readerChunkPath: String? = null
    private var unlockActionId: String? = null

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (slug, chapterIndex) = chapter.getSlugAndIndex(baseUrl)
        val chapterUrl = "$baseUrl/series/$slug/chapter/$chapterIndex"

        val payload = client.get(chapterUrl, rscHeaders, Fetch.XHR).body.string()
        val ticket = extractPagesTicket(payload) ?: error("Tiket halaman chapter tidak ditemukan")

        val images = unlockChapterPages(chapterUrl, ticket)
        if (images.isEmpty()) {
            error("Halaman chapter tidak bisa dibuka. Coba lagi sebentar lagi.")
        }

        return images.mapIndexed { index, imageUrl -> Page(index, url = chapterUrl, imageUrl = imageUrl) }
    }

    /**
     * Mirrors the site reader: exchanges the ticket for the page list, and solves the Turnstile
     * challenge the server demands before it hands the images over.
     */
    private suspend fun unlockChapterPages(chapterUrl: String, ticket: String): List<String> {
        var actionId = unlockChapterPagesActionId(chapterUrl)

        repeat(2) { attempt ->
            runCatching { unlockChapterPages(chapterUrl, actionId, listOf(ticket)) }
                .getOrNull()
                ?.takeIf { it.images.isNotEmpty() }
                ?.let { return it.images }

            runCatching { getTurnstileToken(chapterUrl, TURNSTILE_SITE_KEY, action = TURNSTILE_ACTION) }
                .getOrNull()
                ?.let { token ->
                    runCatching { unlockChapterPages(chapterUrl, actionId, listOf(ticket, token)) }
                        .getOrNull()
                        ?.takeIf { it.images.isNotEmpty() }
                        ?.let { return it.images }
                }

            // Action ids rotate on every site deployment, so rediscover once before giving up.
            if (attempt == 0) {
                readerChunkPath = null
                unlockActionId = null
                actionId = unlockChapterPagesActionId(chapterUrl)
            }
        }

        return emptyList()
    }

    private suspend fun unlockChapterPages(
        chapterUrl: String,
        actionId: String,
        arguments: List<String>,
    ): UnlockResult {
        val actionHeaders = headers.newBuilder()
            .set("Next-Action", actionId)
            .set("Accept", "text/x-component")
            .build()

        val body = client.post(chapterUrl, actionHeaders, Fetch.XHR, arguments.toJsonRequestBody()).body.string()
        return UnlockResult(
            reason = REASON_REGEX.find(body)?.groupValues?.get(1),
            images = IMAGE_REGEX.findAll(body).map { it.value }.toList(),
        )
    }

    private suspend fun unlockChapterPagesActionId(chapterUrl: String): String {
        val html = client.get(chapterUrl, headers, Fetch.DOCUMENT).body.string()
        val chunkPath = READER_CHUNK_REGEX.find(html)?.value ?: error("Chunk reader tidak ditemukan")

        if (chunkPath == readerChunkPath) {
            unlockActionId?.let { return it }
        }

        val chunk = client.get("$baseUrl$chunkPath", headers, Fetch.DOCUMENT).body.string()
        val actionId = ACTION_ID_REGEX.find(chunk)?.groupValues?.get(1)
            ?: error("Aksi unlockChapterPages tidak ditemukan")

        readerChunkPath = chunkPath
        unlockActionId = actionId
        return actionId
    }

    override fun getImageRequest(page: Page): Request = super.getImageRequest(page).newBuilder()
        .fetch(Fetch.IMAGE.from(page.url))
        .build()

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val dto = client.get("$baseUrl/browse", rscHeaders, Fetch.XHR).extractNextJs<FilterPayloadDto> {
            it is JsonObject && "genres" in it
        } ?: error("Failed to fetch filter data")
        return dto.genres.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreItem>>()?.map { it.toPair() }
        return FilterList(
            buildList {
                add(SortFilter())
                add(StatusFilter())
                add(FormatFilter())
                add(TypeFilter())
                add(AdaptationFilter())
                add(MinChapterFilter())
                if (!genres.isNullOrEmpty()) {
                    add(GenreFilter(genres.toTypedArray()))
                }
            },
        )
    }
}
