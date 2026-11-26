package com.ninerouter.android.ui

import android.content.Context
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.ninerouter.android.data.Store
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime translation layer, mirroring the upstream 9Router i18n: the English
 * literal is the dictionary key and the app falls back to it when a locale has
 * no entry. Dynamic strings are handled by `{0}` templates that the runtime
 * matches with a regular expression.
 *
 * Note: the whole app is written in this package, and this file deliberately
 * shadows `androidx.compose.material3.Text` so every call site translates
 * without having to wrap each string by hand.
 */
object I18n {
    /** Latest translator instance, for calls outside composition (toasts). */
    @Volatile
    var current: Translator = Translator.identity()

    val RTL_TAGS = setOf("ar", "he", "fa", "ur")
}

data class UiLocale(val tag: String, val name: String, val flag: String)

/** Locale catalogue shown in the picker; mirrors src/shared/constants/locales.js. */
val SUPPORTED_LOCALES: List<UiLocale> = listOf(
    UiLocale("en", "English", "🇺🇸"),
    UiLocale("vi", "Tiếng Việt", "🇻🇳"),
    UiLocale("zh-CN", "简体中文", "🇨🇳"),
    UiLocale("zh-TW", "繁體中文", "🇹🇼"),
    UiLocale("ja", "日本語", "🇯🇵"),
    UiLocale("pt-BR", "Português (Brasil)", "🇧🇷"),
    UiLocale("pt-PT", "Português (Portugal)", "🇵🇹"),
    UiLocale("ko", "한국어", "🇰🇷"),
    UiLocale("es", "Español", "🇪🇸"),
    UiLocale("de", "Deutsch", "🇩🇪"),
    UiLocale("fr", "Français", "🇫🇷"),
    UiLocale("he", "עברית", "🇮🇱"),
    UiLocale("ar", "العربية", "🇸🇦"),
    UiLocale("ru", "Русский", "🇷🇺"),
    UiLocale("pl", "Polski", "🇵🇱"),
    UiLocale("cs", "Čeština", "🇨🇿"),
    UiLocale("nl", "Nederlands", "🇳🇱"),
    UiLocale("tr", "Türkçe", "🇹🇷"),
    UiLocale("uk", "Українська", "🇺🇦"),
    UiLocale("tl", "Tagalog", "🇵🇭"),
    UiLocale("id", "Indonesia", "🇮🇩"),
    UiLocale("th", "ไทย", "🇹🇭"),
    UiLocale("km", "ខ្មែរ", "🇰🇭"),
    UiLocale("hi", "हिन्दी", "🇮🇳"),
    UiLocale("bn", "বাংলা", "🇧🇩"),
    UiLocale("ur", "اردو", "🇵🇰"),
    UiLocale("ro", "Română", "🇷🇴"),
    UiLocale("sv", "Svenska", "🇸🇪"),
    UiLocale("it", "Italiano", "🇮🇹"),
    UiLocale("el", "Ελληνικά", "🇬🇷"),
    UiLocale("hu", "Magyar", "🇭🇺"),
    UiLocale("fi", "Suomi", "🇫🇮"),
    UiLocale("da", "Dansk", "🇩🇰"),
    UiLocale("no", "Norsk", "🇳🇴"),
    UiLocale("fa", "فارسی", "🇮🇷"),
)

fun isSupportedLocale(tag: String): Boolean = tag == "en" || SUPPORTED_LOCALES.any { it.tag == tag }

fun localeDisplayName(tag: String): String =
    SUPPORTED_LOCALES.firstOrNull { it.tag == tag }?.name ?: tag

/** System language mapped onto the catalogue, e.g. `in` -> `id`, `pt` -> `pt-BR`. */
fun systemLocaleTag(context: Context): String {
    val locales = context.resources.configuration.locales
    val locale = if (locales.size() > 0) locales[0] else Locale.getDefault()
    val language = locale.language.lowercase(Locale.ROOT)
    val country = locale.country.uppercase(Locale.ROOT)
    return when (language) {
        "zh" -> if (country == "TW" || country == "HK" || country == "MO") "zh-TW" else "zh-CN"
        "pt" -> if (country == "PT") "pt-PT" else "pt-BR"
        "in", "id" -> "id"
        "iw", "he" -> "he"
        "no", "nb", "nn" -> "no"
        "tl", "fil" -> "tl"
        else -> language
    }
}

fun resolveLocale(setting: String, context: Context): String {
    if (setting.isNotBlank() && isSupportedLocale(setting)) return setting
    val system = systemLocaleTag(context)
    return if (isSupportedLocale(system)) system else "en"
}

/** Parsed dictionary. `en` is identity, so no asset is read for it. */
class Translator private constructor(
    val tag: String,
    private val exact: Map<String, String>,
    private val templates: List<Template>,
) {
    val isRtl: Boolean get() = tag in I18n.RTL_TAGS
    private val cache = ConcurrentHashMap<String, String>()

    fun translate(text: String?): String? {
        if (text.isNullOrEmpty() || tag == "en") return text
        cache[text]?.let { return it }
        exact[text]?.let { cache[text] = it; return it }

        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && trimmed != text) {
            val inner = translate(trimmed)
            if (inner != null && inner != trimmed) {
                val out = text.replace(trimmed, inner)
                cache[text] = out
                return out
            }
            cache[text] = text
            return text
        }
        for (template in templates) {
            val match = template.regex.matchEntire(trimmed) ?: continue
            val out = template.render(match)
            cache[text] = out
            return out
        }
        cache[text] = text
        return text
    }

    private class Template(
        val regex: Regex,
        private val translated: String,
        private val sourceGroups: Map<Int, Int>,
    ) {
        fun render(match: MatchResult): String = PLACEHOLDER.replace(translated) { hole ->
            val placeholder = hole.value.substring(1, hole.value.length - 1).toIntOrNull()
            val group = placeholder?.let(sourceGroups::get)
            group?.let { match.groups[it]?.value }.orEmpty()
        }
    }

    companion object {
        fun identity(): Translator = Translator("en", emptyMap(), emptyList())

        internal fun fromMap(tag: String, entries: Map<String, String>): Translator {
            val exact = HashMap<String, String>()
            val templates = ArrayList<Template>()
            entries.forEach { (key, value) ->
                if (PLACEHOLDER.containsMatchIn(key)) templates += compile(key, value)
                else exact[key] = value
            }
            templates.sortByDescending { it.regex.pattern.length }
            return Translator(tag, exact, templates)
        }

        fun load(context: Context, tag: String): Translator {
            if (tag == "en") return identity()
            val exact = HashMap<String, String>()
            val templates = ArrayList<Template>()
            runCatching {
                val raw = context.assets.open("i18n/$tag.json").bufferedReader().use { it.readText() }
                val json = JSONObject(raw)
                for (key in json.keys()) {
                    val value = json.optString(key)
                    if (value.isBlank() || value == key) continue
                    val sourceHoles = PLACEHOLDER.findAll(key).map { it.value }.toSet()
                    val translatedHoles = PLACEHOLDER.findAll(value).map { it.value }.toSet()
                    if (sourceHoles != translatedHoles) continue
                    if (sourceHoles.isEmpty()) {
                        exact[key] = value
                    } else {
                        templates += compile(key, value)
                    }
                }
            }
            templates.sortByDescending { it.regex.pattern.length }
            return Translator(tag, exact, templates)
        }

        private val PLACEHOLDER = Regex("""\{\d+\}""")

        private fun compile(key: String, value: String): Template {
            val pattern = StringBuilder("^")
            val sourceGroups = HashMap<Int, Int>()
            var cursor = 0
            var group = 1
            for (hole in PLACEHOLDER.findAll(key)) {
                val literal = Regex.escape(key.substring(cursor, hole.range.first))
                val placeholder = hole.value.substring(1, hole.value.length - 1).toIntOrNull()
                if (placeholder != null) sourceGroups[placeholder] = group
                pattern.append(literal).append("(.*?)")
                group++
                cursor = hole.range.last + 1
            }
            pattern.append(Regex.escape(key.substring(cursor))).append('$')
            return Template(Regex(pattern.toString(), RegexOption.DOT_MATCHES_ALL), value, sourceGroups)
        }
    }
}

val LocalTranslator = compositionLocalOf { Translator.identity() }

/** Wraps the tree with the active locale, its dictionary and text direction. */
@Composable
fun I18nProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val revision = storeRevision().value
    val setting = Store.state.settings.uiLocale
    val tag = resolveLocale(setting, context)
    val translator = remember(tag) { Translator.load(context.applicationContext, tag) }
    I18n.current = translator
    CompositionLocalProvider(
        LocalTranslator provides translator,
        LocalLayoutDirection provides if (translator.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        content()
    }
}

/** Composable lookup for strings that are not rendered through [Text]. */
@Composable
fun t(text: String): String = LocalTranslator.current.translate(text) ?: text

/** Non-composable lookup for toasts and semantics lambdas. */
fun tr(text: String): String = I18n.current.translate(text) ?: text

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
    translate: Boolean = true,
) {
    val translated = if (translate) LocalTranslator.current.translate(text) ?: text else text
    MaterialText(
        text = translated,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout ?: {},
        style = style,
    )
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
    translate: Boolean = true,
) {
    val translator = LocalTranslator.current
    val translated = if (translate && text.spanStyles.isEmpty() && text.paragraphStyles.isEmpty()) {
        AnnotatedString(translator.translate(text.text) ?: text.text)
    } else {
        text
    }
    MaterialText(
        text = translated,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        inlineContent = inlineContent,
        onTextLayout = onTextLayout ?: {},
        style = style,
    )
}
