package io.github.landrop.settings

import java.io.File
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element

class LocaleResourcesTest {
    @Test
    fun translatedFormatArgumentsMatchDefaults() {
        val defaults = resources("values")
        val chinese = resources("values-zh")
        val argument = Pattern.compile("%(?:[0-9]+\\$)?[a-zA-Z]")
        fun arguments(text: String): List<String> {
            val matches = argument.matcher(text)
            return buildList { while (matches.find()) add(matches.group()) }.sorted()
        }
        for ((name, value) in defaults) {
            assertEquals(name, arguments(value), arguments(chinese.getValue(name)))
        }
    }

    @Test
    fun appDialogButtonsDoNotDependOnSystemLanguage() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val systemButtons = Regex("android\\.R\\.string\\.(cancel|ok)\\b")
        val affected = root.walkTopDown().filter { it.extension == "kt" && systemButtons.containsMatchIn(it.readText()) }.toList()
        assertEquals("Use app-owned button strings so OEM/system locales cannot override the app language", emptyList<File>(), affected)
    }

    @Test
    fun defaultResourcesProvideEnglishFallback() {
        val strings = resources("values")
        assertEquals("Settings", strings["string/settings"])
        assertEquals("File transfers", strings["string/notification_channel_transfer"])
        assertEquals("Send content", strings["string/tv_send_content"])
    }

    @Test
    fun chineseTranslationCoversEveryDefaultTextResource() {
        val defaults = resources("values")
        val chinese = resources("values-zh")
        assertEquals(defaults.keys, chinese.keys)
        assertEquals("设置", chinese["string/settings"])
        assertEquals("文件传输", chinese["string/notification_channel_transfer"])
        assertEquals("发送内容", chinese["string/tv_send_content"])
    }

    private fun resources(directory: String): Map<String, String> {
        val root = listOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.isDirectory }
        val files = File(root, directory).listFiles { file -> file.extension == "xml" }
        assertNotNull("Missing resource directory: $directory", files)
        val result = linkedMapOf<String, String>()
        for (file in requireNotNull(files)) {
            val children = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                if (element.tagName !in listOf("string", "plurals", "string-array")) continue
                val key = "${element.tagName}/${element.getAttribute("name")}"
                assertNull("Duplicate text resource: $key", result.put(key, element.textContent))
            }
        }
        return result
    }
}
