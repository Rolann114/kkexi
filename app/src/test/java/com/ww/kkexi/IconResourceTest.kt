package com.ww.kkexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 应用图标资源的静态校验。
 *
 * ## 为什么用测试来查图标
 * 图标没法用 uiautomator 验证：桌面显示由**启动器缓存**决定，
 * 装完新版仍可能显示旧图标（实测过：华为启动器一直给白底蓝线的旧图）。
 * 而图标的**几何与配色**是可以自动检查的——画歪了、超出安全区、
 * 前景色和背景色撞了，这些都能在单测里崩给你看。
 *
 * 真正「看起来对不对」还得靠人眼看一次，但那至少不会出错在图省事的层面。
 */
class IconResourceTest {

    private fun resDir(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 res 目录，试过：${candidates.map { it.absolutePath }}")
    }

    private fun read(relative: String): String {
        val file = File(resDir(), relative)
        assertTrue("缺少资源：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** 从 `<path ... />` 里抠出属性值，避免为解析 XML 引依赖。 */
    private fun attr(tag: String, name: String): String? =
        Regex("""android:$name\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun paths(content: String): List<String> =
        Regex("""<path\b[^>]*/>""", RegexOption.DOT_MATCHES_ALL).findAll(content).map { it.value }.toList()

    @Test
    fun background_is_a_full_bleed_gradient_in_the_brand_blues() {
        val content = read("drawable/ic_launcher_background.xml")

        // 自适应图标的背景必须铺满 108×108 画布，否则会出现透明边
        assertTrue(
            "背景应是一条铺满画布的 path",
            content.contains("""M0,0h108v108h-108z"""),
        )
        assertTrue("背景应是渐变（gradient）而不是纯色", content.contains("<gradient"))
        assertEquals("two stops", 2, Regex("""<item\b""").findAll(content).count())

        // 两个色标都是蓝色系：B 通道明显高于 R
        for (tag in Regex("""<item\b[^>]*/>""").findAll(content).map { it.value }) {
            val color = attr(tag, "color")?.removePrefix("#FF") ?: continue
            val value = color.toLong(16)
            val r = (value shr 16) and 0xFF
            val g = (value shr 8) and 0xFF
            val b = value and 0xFF
            assertTrue("渐变两端都应偏蓝，实际 #$color（r=$r g=$g b=$b）", b > r && b > g)
        }
    }

    @Test
    fun foreground_is_white_ink_inside_the_safe_zone() {
        val content = read("drawable/ic_launcher_foreground.xml")
        val all = paths(content)
        assertTrue("前景至少要有 6 条 path（挂环×2、外框、表头、三行安排），实际 ${all.size}", all.size >= 6)

        // 前景必须是白色线稿：系统会把外围裁掉，只有白色在深蓝底上看得清。
        // 用 strokeColor 或 fillColor 都算。
        for (tag in all) {
            val ink = attr(tag, "fillColor") ?: attr(tag, "strokeColor")
            assertTrue("每条 path 都要指定颜色：$tag", ink != null)
            val isWhite = ink!!.equals("#FFFFFF", ignoreCase = true)
            val isTransparent = ink.equals("#00000000", ignoreCase = true)
            assertTrue("前景应为白色线稿，实际 $ink（$tag）", isWhite || isTransparent)
        }
    }

    @Test
    fun foreground_stays_inside_the_72dp_safe_zone() {
        /*
         * 自适应图标的安全区是画布中间的 72×72（18..90）。超出这个范围的图形，
         * 在圆形 / 水滴等遮罩下会被切掉。
         *
         * ## 抠坐标时踩的两个坑（都在这条测试里撞过）
         * 1. 弧线命令 `a rx,ry rot large-arc sweep x,y` 里的 `0 0 1` 是**标志位**，
         *    被当成坐标就会误报「1 越界」；
         * 2. `h13` / `v-1` 里的 13 / 1 是**相对位移量**，不是坐标位置，
         *    误报「13 越界」。
         *
         * 做法：先整段剔掉 `h`/`v` 指令，再滤掉不大于 10 的数字（弧线标志位与圆角半径
         * 本文件里最大是 10），剩下的才是绝对坐标。
         *
         * 局限：这是启发式而不是真正的 path 解析器——加了半径大于 10 的圆角要同步放宽阈值。
         */
        val content = read("drawable/ic_launcher_foreground.xml")
        val numbers = Regex("""android:pathData\s*=\s*"([^"]*)"""")
            .findAll(content)
            .flatMap { match ->
                // 剔除 h/v 相对位移指令（含负号与小数）
                val absoluteOnly = match.groupValues[1].replace(Regex("""[hvHV][-\d.]+"""), " ")
                Regex("""-?\d+(?:\.\d+)?""").findAll(absoluteOnly).map { it.value.toDouble() }
            }
            .filter { it > 10.0 }   // 滤掉弧线标志位与半径
            .toList()

        assertTrue("前景里应该有坐标数字", numbers.isNotEmpty())
        val outOfZone = numbers.filter { it < 18.0 || it > 90.0 }
        assertTrue(
            "这些坐标超出了 72dp 安全区（18..90），在圆形遮罩下会被切掉：$outOfZone",
            outOfZone.isEmpty(),
        )
    }

    @Test
    fun foreground_does_not_paint_the_whole_canvas() {
        // 前景里出现铺满画布的路径时，会被裁成一个大色块（背景已经在背景层画了）。
        val content = read("drawable/ic_launcher_foreground.xml")
        assertTrue(
            "前景不应包含铺满画布的路径",
            !content.contains("M0,0h108v108h-108z"),
        )
    }

    @Test
    fun adaptive_icon_declares_background_foreground_and_monochrome() {
        // monochrome 是 Android 13 的主题图标层。缺了它，开了主题图标后这个 App 会显示成一团灰。
        for (name in listOf("mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml")) {
            val content = read(name)
            assertTrue("$name 缺 background", content.contains("<background"))
            assertTrue("$name 缺 foreground", content.contains("<foreground"))
            assertTrue("$name 缺 monochrome", content.contains("<monochrome"))
        }
    }

    @Test
    fun manifest_points_at_the_adaptive_icon_not_a_system_drawable() {
        // 第一版图标是 @android:drawable/ic_menu_my_calendar——装上去是系统的通用日历图标。
        val manifest = File(resDir().parentFile, "AndroidManifest.xml")
        assertTrue("找不到 AndroidManifest.xml：${manifest.absolutePath}", manifest.isFile)
        val content = manifest.readText()
        assertTrue("清单应引用自己的 mipmap 图标", content.contains("""android:icon="@mipmap/ic_launcher""""))
        assertTrue("圆形图标也应指向自己的资源", content.contains("""android:roundIcon="@mipmap/ic_launcher_round""""))
        assertTrue(
            "不应再引用系统 drawable 作为图标",
            !content.contains("""android:icon="@android:drawable"""),
        )
    }
}
