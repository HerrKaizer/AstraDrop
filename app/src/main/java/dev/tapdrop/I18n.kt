package dev.tapdrop

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Translations, SolCube's way: the English text is the key, and assets/i18n/<lang>.json maps it to
 * the translation (missing → English). Two settings: the app's language and the language of the
 * page the iPhone sees, which can follow the app, follow the iPhone, or be set on its own.
 *
 * Plurals: the key is "one|other" in English; a translation lists its forms the same way
 * ("one|few|many" for Russian and Polish, one form for Chinese). %d is the number.
 */
object I18n {
    val LANGS = listOf("en", "ru", "pt", "pl", "es", "de", "fr", "it", "zh")
    val CHOICES = listOf("system") + LANGS
    val WEB_CHOICES = listOf("same", "iphone") + LANGS

    private lateinit var app: Context
    private val maps = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var appLang = "en"

    private fun prefs() = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        appLang = resolve(saved(ctx))
    }

    fun saved(ctx: Context): String = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("app_lang", "system") ?: "system"
    fun savedWeb(ctx: Context): String = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("web_lang", "same") ?: "same"

    fun setApp(ctx: Context, code: String) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("app_lang", code).commit()
        appLang = resolve(code)
    }

    fun setWeb(ctx: Context, code: String) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("web_lang", code).commit()
    }

    fun current() = appLang

    private fun resolve(code: String): String =
        if (code == "system") deviceLang() else code.takeIf { it in LANGS } ?: "en"

    private fun deviceLang(): String = Locale.getDefault().language.let { if (it in LANGS) it else "en" }

    /** The page's language for a browser that sent [acceptLanguage]. */
    fun webLang(acceptLanguage: String?): String = when (val w = if (::app.isInitialized) savedWeb(app) else "same") {
        "same" -> appLang
        "iphone" -> acceptLanguage?.split(',')?.map { it.substringBefore(';').trim().substringBefore('-').lowercase() }
            ?.firstOrNull { it in LANGS } ?: "en"
        else -> w.takeIf { it in LANGS } ?: "en"
    }

    fun name(code: String) = when (code) {
        "system" -> t("Phone's language") + " (" + nativeName(deviceLang()) + ")"
        else -> nativeName(code)
    }

    fun webName(code: String) = when (code) {
        "same" -> t("Same as the app")
        "iphone" -> t("The iPhone's language")
        else -> nativeName(code)
    }

    fun nativeName(code: String) = when (code) {
        "en" -> "English"
        "ru" -> "Русский"
        "pt" -> "Português"
        "pl" -> "Polski"
        "es" -> "Español"
        "de" -> "Deutsch"
        "fr" -> "Français"
        "it" -> "Italiano"
        "zh" -> "中文"
        else -> code
    }

    private fun map(lang: String): Map<String, String> {
        if (lang == "en" || !::app.isInitialized) return emptyMap()
        return maps.getOrPut(lang) {
            try {
                val o = JSONObject(app.assets.open("i18n/$lang.json").bufferedReader().use { it.readText() })
                val m = HashMap<String, String>(o.length() * 2)
                for (k in o.keys()) m[k] = o.getString(k)
                m
            } catch (_: Exception) { emptyMap() }
        }
    }

    /** [en] in the app's language. */
    fun t(en: String): String = tr(appLang, en)

    /** [en] in [lang] (the page uses this with its own language). */
    fun tr(lang: String, en: String): String = map(lang)[en] ?: en

    /** "%d file" / "%d files" in the app's language, with the number filled in. */
    fun plural(n: Int, one: String, other: String) = pluralIn(appLang, n, one, other)

    fun pluralIn(lang: String, n: Int, one: String, other: String): String {
        val forms = map(lang)["$one|$other"]?.split('|') ?: listOf(one, other)
        val i = when (lang) {
            "ru", "pl" -> {
                val m10 = n % 10; val m100 = n % 100
                when {
                    lang == "ru" && m10 == 1 && m100 != 11 -> 0
                    lang == "pl" && n == 1 -> 0
                    m10 in 2..4 && m100 !in 12..14 -> 1
                    else -> 2
                }
            }
            "zh" -> 0
            "fr", "pt" -> if (n == 0 || n == 1) 0 else 1
            else -> if (n == 1) 0 else 1
        }
        return forms[i.coerceAtMost(forms.lastIndex)].replace("%d", n.toString())
    }
}
