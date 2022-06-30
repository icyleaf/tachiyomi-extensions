package eu.kanade.tachiyomi.extension.zh.zerobyw

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.text.InputType
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class Zerobyw() : ConfigurableSource, ParsedHttpSource() {
    override val name: String = "zero搬运网"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = false

    // Url can be found at https://eprendre2.coding.net/p/zerobyw/d/zerobyw/git/raw/master/url.json
    override val baseUrl: String = "http://www.zerobywps8.com"

    // Popular
    // Website does not provide popular manga, this is actually latest manga

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/plugin.php?id=jameson_manhua&c=index&a=ku&&page=$page", headers)

    override fun popularMangaNextPageSelector(): String = "div.pg > a.nxt"
    override fun popularMangaSelector(): String = "div.uk-card"
    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = getTitle(element.select("p.mt5 > a").text())
        setUrlWithoutDomain(element.select("p.mt5 > a").attr("abs:href"))
        thumbnail_url = element.select("img").attr("src")
    }

    // Latest

    override fun latestUpdatesRequest(page: Int) = throw Exception("Not used")
    override fun latestUpdatesNextPageSelector() = throw Exception("Not used")
    override fun latestUpdatesSelector() = throw Exception("Not used")
    override fun latestUpdatesFromElement(element: Element) = throw Exception("Not used")

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val uri = Uri.parse(baseUrl).buildUpon()
        if (query.isNotBlank()) {
            uri.appendPath("plugin.php")
                .appendQueryParameter("id", "jameson_manhua")
                .appendQueryParameter("a", "search")
                .appendQueryParameter("c", "index")
                .appendQueryParameter("keyword", query)
                .appendQueryParameter("page", page.toString())
        } else {
            uri.appendPath("plugin.php")
                .appendQueryParameter("id", "jameson_manhua")
                .appendQueryParameter("c", "index")
                .appendQueryParameter("a", "ku")
            filters.forEach {
                if (it is UriSelectFilterPath && it.toUri().second.isNotEmpty())
                    uri.appendQueryParameter(it.toUri().first, it.toUri().second)
            }
            uri.appendQueryParameter("page", page.toString())
        }
        return GET(uri.toString(), headers)
    }

    override fun searchMangaNextPageSelector(): String = "div.pg > a.nxt"
    override fun searchMangaSelector(): String = "a.uk-card, div.uk-card"
    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = getTitle(element.select("p.mt5").text())
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
        thumbnail_url = element.select("img").attr("src")
    }

    // Details

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = getTitle(document.select("li.uk-active > h3.uk-heading-line").text())
        thumbnail_url = document.select("div.uk-width-medium > img").attr("abs:src")
        author = document.selectFirst("div.cl > a.uk-label").text().substring(3)
        artist = author
        genre = document.select("div.cl > a.uk-label, div.cl > span.uk-label").eachText()
            .joinToString(", ")
        description = document.select("li > div.uk-alert").html().replace("<br>", "")
        status = when (document.select("div.cl > span.uk-label").last().text()) {
            "连载中" -> SManga.ONGOING
            "已完结" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // Chapters

    override fun chapterListSelector(): String = "div.uk-grid-collapse > div.muludiv"
    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.select("a.uk-button-default").attr("abs:href"))
        name = element.select("a.uk-button-default").text()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return super.chapterListParse(response).reversed()
    }

    // Pages

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(document: Document): List<Page> = mutableListOf<Page>().apply {
        val images = document.select("div.uk-text-center > img")
        if (images.size == 0) {
            var message = document.select("div#messagetext > p")
            if (message.size == 0) {
                message = document.select("div.uk-alert > p")
            }
            if (message.size != 0) {
                throw Exception(message.text())
            }
        }
        images.forEach {
            add(Page(size, "", it.attr("src")))
        }
    }

    override fun imageUrlParse(document: Document): String = throw Exception("Not Used")

    // Filters

    override fun getFilterList() = FilterList(
        Filter.Header("如果使用文本搜索"),
        Filter.Header("过滤器将被忽略"),
        CategoryFilter(),
        StatusFilter(),
        AttributeFilter()
    )

    private class CategoryFilter : UriSelectFilterPath(
        "category_id",
        "分类",
        arrayOf(
            Pair("", "全部"),
            Pair("1", "卖肉"),
            Pair("15", "战斗"),
            Pair("32", "日常"),
            Pair("6", "后宫"),
            Pair("13", "搞笑"),
            Pair("28", "日常"),
            Pair("31", "爱情"),
            Pair("22", "冒险"),
            Pair("23", "奇幻"),
            Pair("26", "战斗"),
            Pair("29", "体育"),
            Pair("34", "机战"),
            Pair("35", "职业"),
            Pair("36", "汉化组跟上，不再更新")
        )
    )

    private class StatusFilter : UriSelectFilterPath(
        "jindu",
        "进度",
        arrayOf(
            Pair("", "全部"),
            Pair("0", "连载中"),
            Pair("1", "已完结")
        )
    )

    private class AttributeFilter : UriSelectFilterPath(
        "shuxing",
        "性质",
        arrayOf(
            Pair("", "全部"),
            Pair("一半中文一半生肉", "一半中文一半生肉"),
            Pair("全生肉", "全生肉"),
            Pair("全中文", "全中文")
        )
    )

    /**
     * Class that creates a select filter. Each entry in the dropdown has a name and a display name.
     * If an entry is selected it is appended as a query parameter onto the end of the URI.
     */
    // vals: <name, display>
    private open class UriSelectFilterPath(
        val key: String,
        displayName: String,
        val vals: Array<Pair<String, String>>
    ) : Filter.Select<String>(displayName, vals.map { it.second }.toTypedArray()) {
        fun toUri() = Pair(key, vals[state].first)
    }

    private fun getTitle(title: String): String {
        val result = Regex("【\\d+").find(title)
        return if (result != null) {
            title.substringBefore(result.value)
        } else {
            title.substringBefore("【")
        }
    }

    private var authCookies = mutableListOf<Cookie>()
    private val username by lazy { getPrefUsername() }
    private val password by lazy { getPrefPassword() }

    // Authorization

    override val client: OkHttpClient = network.client.newBuilder()
        .cookieJar(
            object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    for (cookie in cookies) {
                        authCookies.add(cookie)
                    }
                }

                override fun loadForRequest(url: HttpUrl): MutableList<Cookie> {
                    val requestCookies = mutableListOf<Cookie>()
                    for (cookie in authCookies) {
                        requestCookies.add(cookie)
                    }

                    return requestCookies
                }
            }
        )
        .addInterceptor { authIntercept(it) }
        .build()

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (username.isEmpty() and password.isEmpty()) {
            return chain.proceed(request)
        }

        val requestUrl = request.url.toString()
        if (!requestUrl.contains("member.php") and
            !authCookies.any { cookie -> cookie.name.contains(USER_AUTH_COOKIE, true) }
        ) {
            loginWithCookies()
        }

        return chain.proceed(request)
    }

    private fun loginWithCookies() {
        val authSalt = fetchSalt()

        val loginClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val headerBuilder = headersBuilder()
            .add("Content-Type", "application/x-www-form-urlencoded")

        for (cookie in authSalt.cookies) {
            headerBuilder.add("Cookie", cookie.toString())
        }

        val formBody: RequestBody = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("formhash", authSalt.hash)
            .add("quickforward", "yes")
            .add("cookietime", "2592000")
            .build()

        val loginRequest = POST(
            "$baseUrl/member.php?mod=logging&action=login&loginsubmit=yes&infloat=yes&lssubmit=yes",
            headerBuilder.build(),
            formBody
        )

        val call: Call = loginClient.newCall(loginRequest)
        val response: Response = call.execute()
        if (response.code != 301 || response.header("Set-Cookie") == null) {
            throw IOException("登录失败，检查用户名或密码是否正确。")
        }

        val cookies = Cookie.parseAll(baseUrl.toHttpUrl(), response.headers)
        if (!cookies.any { cookie -> cookie.name.contains(USER_AUTH_COOKIE, true) }) {
            throw IOException("登录失败，检查用户名或密码是否正确。")
        }

        for (cookie in cookies) {
            authCookies.add(cookie)
        }

        response.close()
    }

    private fun fetchSalt(): AuthSalt {
        val indexRequest = GET("$baseUrl/member.php?mod=register", headersBuilder().build())
        val response = client.newCall(indexRequest).execute()
        val cookies = Cookie.parseAll(baseUrl.toHttpUrl(), response.headers)
        val document = response.asJsoup()

        val inputs = document.select(FORM_HASH_SELECTOR)
        val hashValue = inputs
            .first { doc -> doc.attr("name") == "formhash" }
            .attr("value")

        return AuthSalt(hashValue, cookies)
    }

    private class AuthSalt constructor(
        val hash: String,
        val cookies: List<Cookie>
    )

    // Preferences

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun getPrefUsername(): String = preferences.getString(USERNAME, USERNAME_DEFAULT)!!
    private fun getPrefPassword(): String = preferences.getString(PASSWORD, PASSWORD_DEFAULT)!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(screen.editTextPreference(USERNAME, USERNAME_DEFAULT, username))
        screen.addPreference(screen.editTextPreference(PASSWORD, PASSWORD_DEFAULT, password, true))
    }

    private fun PreferenceScreen.editTextPreference(
        title: String,
        default: String,
        value: String,
        isPassword: Boolean = false
    ): androidx.preference.EditTextPreference {
        return androidx.preference.EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value
            this.setDefaultValue(default)
            dialogTitle = title

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType =
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(
                        context,
                        "Restart Tachiyomi to apply new setting.",
                        Toast.LENGTH_LONG
                    ).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
    }

    companion object {
        private const val USER_AUTH_COOKIE = "kd5S_2132_auth"
        private const val USERNAME = "USERNAME"
        private const val USERNAME_DEFAULT = ""
        private const val PASSWORD = "PASSWORD"
        private const val PASSWORD_DEFAULT = ""
        private const val FORM_HASH_SELECTOR = "#scbar_form input[type=hidden]"
    }
}
