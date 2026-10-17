package com.masterofchessstrategy.games.go.data

import android.content.Context
import android.util.Xml
import com.masterofchessstrategy.games.go.GoLearningLibrary
import com.masterofchessstrategy.games.go.GoLearningProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Migration reads committed disk state, never a SharedPreferences in-memory map. */
internal fun interface GoLearningLegacySource {
    suspend fun readCommitted(): GoLearningProgress
}

internal class GoLearningPreferencesDiskSource(private val file: File) : GoLearningLegacySource {
    constructor(context: Context) : this(File(context.applicationContext.dataDir, "shared_prefs/go-learning-v1.xml"))

    override suspend fun readCommitted(): GoLearningProgress = withContext(Dispatchers.IO) {
        val bytes = GoLegacyDiskSnapshot.read(file, MAX_XML_BYTES) ?: return@withContext GoLearningProgress()
        GoLearningPreferencesXml.decode(bytes)
    }

    companion object { const val MAX_XML_BYTES = 16 * 1024 }
}

/** The historical writer emits only map/int/string/set. Unknown future data is retained, not ignored. */
internal object GoLearningPreferencesXml {
    fun decode(bytes: ByteArray): GoLearningProgress {
        if (bytes.size !in 1..GoLearningPreferencesDiskSource.MAX_XML_BYTES) throw IOException("围棋旧教程进度长度无效")
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val upper = text.uppercase(Locale.ROOT)
            require("<!DOCTYPE" !in upper && "<!ENTITY" !in upper) { "XML declarations are not supported" }
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            fun next(): Int {
                while (true) {
                    val event = parser.nextToken()
                    require(event != XmlPullParser.DOCDECL && event != XmlPullParser.ENTITY_REF)
                    if (event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG) {
                        require(parser.namespace.isNullOrEmpty() && parser.getNamespaceCount(parser.depth) == 0)
                    }
                    if (event == XmlPullParser.COMMENT || event == XmlPullParser.IGNORABLE_WHITESPACE ||
                        event == XmlPullParser.TEXT && parser.text.isBlank()) continue
                    return event
                }
            }
            fun string(): String {
                val value = StringBuilder()
                while (true) {
                    when (parser.nextToken()) {
                        XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE -> value.append(parser.text)
                        XmlPullParser.COMMENT -> Unit
                        XmlPullParser.END_TAG -> {
                            require(parser.name == "string" && parser.namespace.isNullOrEmpty())
                            return value.toString()
                        }
                        else -> throw IllegalArgumentException("Unexpected legacy string token")
                    }
                    require(value.length <= 128)
                }
            }
            require(next() == XmlPullParser.START_TAG && parser.name == "map" && parser.attributeCount == 0)
            val seen = mutableSetOf<String>()
            var completed: Set<String> = emptySet()
            var last = GoLearningLibrary.lessons.first().id
            while (next() != XmlPullParser.END_TAG) {
                require(parser.eventType == XmlPullParser.START_TAG)
                val tag = parser.name
                val key = requireNotNull(parser.getAttributeValue(null, "name"))
                require(seen.add(key)) { "Duplicate legacy preference key" }
                when (key) {
                    "version" -> {
                        require(tag == "int" && parser.attributeCount == 2 &&
                            parser.getAttributeValue(null, "value") == GoLearningLibrary.CONTENT_VERSION.toString())
                        require(next() == XmlPullParser.END_TAG && parser.name == "int")
                    }
                    "last" -> {
                        require(tag == "string" && parser.attributeCount == 1)
                        last = string()
                    }
                    "completed" -> {
                        require(tag == "set" && parser.attributeCount == 1)
                        val ids = linkedSetOf<String>()
                        while (next() != XmlPullParser.END_TAG) {
                            require(parser.eventType == XmlPullParser.START_TAG && parser.name == "string" && parser.attributeCount == 0)
                            require(ids.add(string())) { "Duplicate legacy lesson" }
                            require(ids.size <= GoLearningLibrary.lessons.size)
                        }
                        require(parser.name == "set")
                        completed = ids
                    }
                    else -> throw IllegalArgumentException("Unknown legacy preference key")
                }
            }
            require(parser.name == "map" && next() == XmlPullParser.END_DOCUMENT)
            return GoLearningProgressCodec.freeze(GoLearningProgress(completed, last))
        } catch (error: IllegalArgumentException) {
            throw IOException("围棋旧教程进度无效或版本不兼容，原文件已保留", error)
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IOException("围棋旧教程进度不是有效UTF-8", error)
        } catch (error: XmlPullParserException) {
            throw IOException("围棋旧教程XML无效，原文件已保留", error)
        }
    }
}
