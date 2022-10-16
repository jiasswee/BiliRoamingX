package me.iacn.biliroaming.hook

import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.LineBackgroundSpan
import android.text.style.MaskFilterSpan
import android.text.style.StyleSpan
import me.iacn.biliroaming.*
import me.iacn.biliroaming.API.*
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.network.BiliRoamingApi
import me.iacn.biliroaming.hook.BangumiSeasonHook.Companion.lastSeasonInfo
import me.iacn.biliroaming.hook.BangumiPlayUrlHook.Companion.countDownLatch
import me.iacn.biliroaming.utils.*
import org.json.JSONArray
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt


class SubtitleHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        var currentSubtitles = listOf<SubtitleItem>()

        val backgroundSpan = { backgroundColor: Int, textSize: Int ->
            LineBackgroundSpan { canvas, paint, left, right, top, _, bottom, text, start, end, _ ->
                val ts = paint.textSize
                paint.textSize = textSize.toFloat()
                val width = paint.measureText(text, start, end).roundToInt()
                val textLeft = (right + left - width) / 2
                val color = paint.color
                val rect = Rect()
                rect.set(textLeft, top, textLeft + width, bottom)
                paint.color = backgroundColor
                canvas.drawRect(rect, paint)
                paint.textSize = ts
                paint.color = color
            }
        }

        fun subtitleStylizeRunner(
            subtitle: SpannableString,
            start: Int,
            end: Int,
            flags: Int,
            blurSolid: Int,
            fontColor: String,
            fontSize: Int,
            bgColor: String,
            strokeColor: String,
            strokeWidth: Float,
            fixBreak: Boolean
        ) {
            val subtitleBlurSolid = blurSolid.toString() + "f"
            val fc = Color.parseColor("#$fontColor")
            val sc = Color.parseColor("#$strokeColor")
            if (fixBreak)
                (start until end).forEach { i ->
                    subtitle.setSpan(
                        StrokeSpan(fc, sc, strokeWidth),
                        i,
                        i + 1,
                        flags
                    )
                }
            else
                subtitle.setSpan(StrokeSpan(fc, sc, strokeWidth), start, end, flags)
            subtitle.setSpan(
                AbsoluteSizeSpan(fontSize, false),
                start,
                end,
                flags
            )
            subtitle.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                flags
            )
            if (blurSolid != 0) {
                subtitle.setSpan(
                    MaskFilterSpan(
                        BlurMaskFilter(
                            subtitleBlurSolid.toFloat(),
                            BlurMaskFilter.Blur.SOLID
                        )
                    ),
                    start,
                    end,
                    flags
                )
            }
            // should be drawn the last
            subtitle.setSpan(
                backgroundSpan(Color.parseColor("#$bgColor"), fontSize),
                start,
                end,
                flags
            )
        }
    }

    private val enableSubDownload by lazy {
        sPrefs.getBoolean("main_func", false)
                && sPrefs.getBoolean("enable_download_subtitle", false)
    }

    private val mainFunc by lazy { sPrefs.getBoolean("main_func", false) }
    private val generateSubtitle by lazy { sPrefs.getBoolean("auto_generate_subtitle", false) }
    private val addCloseSubtitle by lazy { mainFunc && getVersionCode(packageName) >= 6750300 }
    private val customSubtitle by lazy { sPrefs.getBoolean("custom_subtitle", false) }
    private val removeBg by lazy { sPrefs.getBoolean("subtitle_remove_bg", true) }
    private val boldText by lazy { sPrefs.getBoolean("subtitle_bold", true) }
    private val fillColor by lazy {
        sPrefs.getString("subtitle_font_color2", null)
            ?.runCatchingOrNull { Color.parseColor("#$this") } ?: Color.WHITE
    }
    private val strokeColor by lazy {
        sPrefs.getString("subtitle_stroke_color", null)
            ?.runCatchingOrNull { Color.parseColor("#$this") } ?: Color.BLACK
    }
    private val strokeWidth by lazy {
        sPrefs.getFloat("subtitle_stroke_width", 5.0F)
    }

    private val closeText =
        currentContext.getString(getResId("Player_option_subtitle_lan_doc_nodisplay", "string"))

    override fun startHook() {
        if (customSubtitle)
            if (instance.subtitleSpanClass != null) {
                hookSubtitleStyle()
            } else {
                hookSubtitleStyleNew()
            }
        if (mainFunc || generateSubtitle || enableSubDownload)
            hookSubtitleList()
    }

    private fun hookSubtitleStyle() {
        instance.chronosSwitchClass?.hookAfterConstructor { param ->
            param.thisObject.javaClass.declaredFields.forEach {
                if (it.type == Boolean::class.javaObjectType) {
                    param.thisObject.setObjectField(it.name, false)
                }
            }
        }

        android.text.SpannableString::class.java.hookBeforeMethod(
            "setSpan",
            Object::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java
        ) { param ->
            if (instance.subtitleSpanClass?.isInstance(param.args[0]) != true) return@hookBeforeMethod
            val (start, end, flags) = listOf(
                param.args[1] as Int,
                param.args[2] as Int,
                param.args[3] as Int
            )
            (param.thisObject as SpannableString).run {
                subtitleStylizeRunner(
                    this, start, end, flags,
                    sPrefs.getInt("subtitle_blur_solid", 1),
                    sPrefs.getString(
                        "subtitle_font_color2",
                        "FFFFFFFF"
                    )!!,
                    sPrefs.getInt("subtitle_font_size", 30),
                    sPrefs.getString(
                        "subtitle_background_color",
                        "20000000"
                    )!!,
                    sPrefs.getString("subtitle_stroke_color", "00000000")!!,
                    sPrefs.getFloat("subtitle_stroke_width", 0F),
                    sPrefs.getBoolean("subtitle_fix_break", false)
                )
                param.result = null
            }
        }
    }

    private fun hookSubtitleStyleNew() {
        val cronCanvasClass = instance.cronCanvasClass ?: return
        if (removeBg) {
            cronCanvasClass.replaceMethod(
                "drawPath",
                Path::class.java,
                Boolean::class.javaPrimitiveType
            ) { null }
        }
        val paintField = cronCanvasClass.getDeclaredField("paint")
            .apply { isAccessible = true }
        val fillColorField = cronCanvasClass.getDeclaredField("fillColor")
            .apply { isAccessible = true }
        val strokeColorField = cronCanvasClass.getDeclaredField("strokeColor")
            .apply { isAccessible = true }
        cronCanvasClass.hookBeforeMethod(
            "drawText",
            String::class.java,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        ) { param ->
            val stroke = param.args[3] as Boolean
            val cronCanvas = param.thisObject

            val paint = paintField.get(cronCanvas) as TextPaint
            if (!stroke && paint.strokeWidth == 0.0F) {
                paint.strokeWidth = strokeWidth
                fillColorField.setInt(cronCanvas, fillColor)
                strokeColorField.setInt(cronCanvas, strokeColor)
                if (boldText)
                    paint.isFakeBoldText = true
                param.args[3] = true
                param.invokeOriginalMethod()
                param.args[3] = false
            }
        }
    }

    private fun hookSubtitleList() {
        "com.bapis.bilibili.community.service.dm.v1.DMMoss".from(mClassLoader)?.hookAfterMethod(
            "dmView", "com.bapis.bilibili.community.service.dm.v1.DmViewReq",
        ) { param ->
            val parseDmViewReply = { r: Any? ->
                r?.let { DmViewReply.parseFrom(it.callMethodAs<ByteArray>("toByteArray")) }
            }

            val extraSubtitles = mutableListOf<SubtitleItem>()
            if (mainFunc) {
                val oid = param.args[0].callMethod("getOid").toString()
                var tryThailand = lastSeasonInfo.containsKey("watch_platform")
                        && lastSeasonInfo["watch_platform"] == "1"
                        && lastSeasonInfo.containsKey(oid)
                        && (param.result == null || param.result.callMethod("getSubtitle")
                    ?.callMethod("getSubtitlesCount") == 0)
                if (!tryThailand && !lastSeasonInfo.containsKey("area")) {
                    countDownLatch = CountDownLatch(1)
                    try {
                        countDownLatch?.await(5, TimeUnit.SECONDS)
                    } catch (ignored: Throwable) {
                    }
                    tryThailand = lastSeasonInfo.containsKey("area")
                            && lastSeasonInfo["area"] == "th"
                }
                if (tryThailand) {
                    val subtitles = if (lastSeasonInfo.containsKey("sb$oid")) {
                        JSONArray(lastSeasonInfo["sb$oid"])
                    } else {
                        val result = BiliRoamingApi.getThailandSubtitles(
                            lastSeasonInfo[oid] ?: lastSeasonInfo["epid"]
                        )?.toJSONObject()
                        if (result != null && result.optInt("code") == 0) {
                            result.optJSONObject("data")
                                ?.optJSONArray("subtitles").orEmpty()
                        } else JSONArray()
                    }
                    if (subtitles.length() != 0) {
                        extraSubtitles += subtitles.toSubtitles()
                    }
                }
            }

            val dmViewReply = if (generateSubtitle) parseDmViewReply(param.result) else null
            if (generateSubtitle) {
                val subtitles = mutableListOf<SubtitleItem>()
                dmViewReply?.subtitle?.subtitlesList?.let { subtitles += it }
                subtitles += extraSubtitles
                if (subtitles.map { it.lan }.let { "zh-Hant" in it && "zh-CN" !in it }) {
                    val origSub = subtitles.first { it.lan == "zh-Hant" }
                    val targetSubUrl = Uri.parse(origSub.subtitleUrl).buildUpon()
                        .appendQueryParameter("zh_converter", "t2cn")
                        .build().toString()

                    subtitleItem {
                        lan = "zh-CN"
                        lanDoc = "简中（生成）"
                        lanDocBrief = "简中"
                        subtitleUrl = targetSubUrl
                        id = origSub.id + 1
                        idStr = id.toString()
                    }.let { extraSubtitles += it }
                }
            }

            if (addCloseSubtitle && param.args[0]
                    .callMethodAs<String>("getSpmid").contains("pgc")
            ) {
                subtitleItem {
                    lan = "nodisplay"
                    lanDoc = closeText
                }.let { extraSubtitles += it }
            }

            if (extraSubtitles.isNotEmpty() || enableSubDownload) {
                val newRes = (dmViewReply ?: parseDmViewReply(param.result)
                ?: dmViewReply { }).copy {
                    subtitle = subtitle.copy {
                        subtitles += extraSubtitles
                        if (enableSubDownload)
                            currentSubtitles = subtitles.filterNot { it.lan == "nodisplay" }
                    }
                }
                if (extraSubtitles.isEmpty()) return@hookAfterMethod

                param.result = (param.method as Method).returnType
                    .callStaticMethod("parseFrom", newRes.toByteArray())
            }
        }

        if (!generateSubtitle) return
        instance.biliCallClass?.hookBeforeMethod(
            instance.setParser(), instance.parserClass
        ) { param ->
            val url = param.thisObject.getObjectField(instance.biliCallRequestField())
                ?.getObjectField(instance.urlField())?.toString()
            if (url?.contains("zh_converter=t2cn") != true)
                return@hookBeforeMethod
            val parser = param.args[0]
            param.args[0] = Proxy.newProxyInstance(
                parser.javaClass.classLoader,
                arrayOf(instance.parserClass)
            ) { _, m, args ->
                val dictReady = if (!SubtitleHelper.dictExist) {
                    runCatchingOrNull {
                        SubtitleHelper.executor.submit(Callable {
                            SubtitleHelper.checkDictUpdate()
                        }).get(60, TimeUnit.SECONDS)
                    } == true || SubtitleHelper.dictExist
                } else true
                val converted = if (dictReady) {
                    runCatching {
                        val responseText = args[0].callMethodAs<String>(instance.string())
                        SubtitleHelper.convert(responseText)
                    }.onFailure {
                        Log.e(it)
                    }.getOrNull()
                        ?: SubtitleHelper.errorResponse(XposedInit.moduleRes.getString(R.string.subtitle_convert_failed))
                } else SubtitleHelper.errorResponse(XposedInit.moduleRes.getString(R.string.subtitle_dict_download_failed))

                runCatchingOrNull {
                    SubtitleHelper.executor.execute {
                        SubtitleHelper.checkDictUpdate().yes {
                            SubtitleHelper.reloadDict()
                        }
                    }
                }

                val mediaType = instance.mediaTypeClass
                    ?.callStaticMethod(
                        instance.get(),
                        "application/json; charset=UTF-8"
                    ) ?: return@newProxyInstance m(parser, *args)
                val responseBody = instance.responseBodyClass
                    ?.callStaticMethod(
                        instance.create(),
                        mediaType,
                        converted
                    ) ?: return@newProxyInstance m(parser, *args)
                m(parser, responseBody)
            }
        }
    }

    private fun JSONArray.toSubtitles(): List<SubtitleItem> {
        val subList = mutableListOf<SubtitleItem>()
        val lanCodes = mutableSetOf<String>()
        for (s in this)
            lanCodes.add(s.optString("key"))
        val replaceHans = "zh-Hans" !in lanCodes
        for (subtitle in this) {
            subtitleItem {
                id = subtitle.optLong("id")
                idStr = subtitle.optLong("id").toString()
                subtitleUrl = subtitle.optString("url")
                lan = subtitle.optString("key")
                    .let { if (it == "cn" && replaceHans) "zh-Hans" else it }
                lanDoc = subtitle.optString("title")
            }.let { subList.add(it) }
        }
        return subList
    }
}
