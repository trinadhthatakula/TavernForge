package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.core.model.JsonLimits
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.IdentityHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

internal class JsonFailure(val diagnostic: Diagnostic) : RuntimeException(diagnostic.message)

internal fun pointerToken(value: String): String = value.replace("~", "~0").replace("/", "~1")

/** Strict syntax and resource checks occur before a map can overwrite duplicate names. */
@OptIn(ExperimentalSerializationApi::class)
internal class BoundedJson(private val limits: JsonLimits) {
    fun parse(bytes: ByteArray): JsonElement {
        if (bytes.size > limits.maxBytes) fail("BYTE_LIMIT", "JSON exceeds the byte limit.")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail("INVALID_UTF8", "JSON must contain valid UTF-8.")
        }
        return Parser(text).parse()
    }

    /** Bounds output during writing, including arbitrary/cyclic externally supplied patch values. */
    fun encode(value: JsonElement): ByteArray = Writer().write(value)

    private inner class Parser(private val text: String) {
        private var index = 0
        private var nodes = 0
        fun parse(): JsonElement {
            whitespace()
            val result = value(1, JsonPath.ROOT)
            whitespace()
            if (index != text.length) error("INVALID_JSON", "Unexpected content after the JSON value.", JsonPath.ROOT)
            return result
        }

        private fun value(depth: Int, path: JsonPath): JsonElement {
            if (depth > limits.maxDepth) error("DEPTH_LIMIT", "JSON exceeds the nesting limit.", path)
            if (++nodes > limits.maxNodes) error("NODE_LIMIT", "JSON exceeds the value-count limit.", path)
            whitespace()
            if (index >= text.length) error("INVALID_JSON", "Expected a JSON value.", path)
            return when (text[index]) {
                '{' -> objectValue(depth, path)
                '[' -> arrayValue(depth, path)
                '"' -> JsonPrimitive(string(path))
                't' -> { literal("true", path); JsonPrimitive(true) }
                'f' -> { literal("false", path); JsonPrimitive(false) }
                'n' -> { literal("null", path); JsonNull }
                '-', in '0'..'9' -> number(path)
                else -> error("INVALID_JSON", "Unexpected character in JSON.", path)
            }
        }

        private fun objectValue(depth: Int, path: JsonPath): JsonObject {
            index++
            whitespace()
            val members = linkedMapOf<String, JsonElement>()
            if (take('}')) return JsonObject(members)
            while (true) {
                if (members.size >= limits.maxCollectionEntries) error("COLLECTION_LIMIT", "Object exceeds the member limit.", path)
                if (index >= text.length || text[index] != '"') error("INVALID_JSON", "Expected an object member name.", path)
                val key = string(path)
                val childPath = path.child(key)
                if (members.containsKey(key)) error("DUPLICATE_KEY", "Duplicate object member name.", childPath)
                whitespace()
                expect(':', path)
                members[key] = value(depth + 1, childPath)
                whitespace()
                if (take('}')) return JsonObject(members)
                expect(',', path)
                whitespace()
            }
        }

        private fun arrayValue(depth: Int, path: JsonPath): JsonArray {
            index++
            whitespace()
            val items = mutableListOf<JsonElement>()
            if (take(']')) return JsonArray(items)
            while (true) {
                if (items.size >= limits.maxCollectionEntries) error("COLLECTION_LIMIT", "Array exceeds the entry limit.", path)
                items += value(depth + 1, path.child(items.size.toString()))
                whitespace()
                if (take(']')) return JsonArray(items)
                expect(',', path)
                whitespace()
            }
        }

        private fun string(path: JsonPath): String {
            expect('"', path)
            val result = StringBuilder()
            while (index < text.length) {
                var c = text[index++]
                if (c == '"') {
                    checkSurrogates(result.toString(), path)
                    return result.toString()
                }
                if (c < ' ') error("INVALID_JSON", "Unescaped control character in string.", path)
                if (c == '\\') {
                    if (index >= text.length) error("INVALID_JSON", "Incomplete string escape.", path)
                    c = when (val escaped = text[index++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            if (index + 4 > text.length) error("INVALID_JSON", "Incomplete Unicode escape.", path)
                            var code = 0
                            repeat(4) {
                                val digit = when (val hex = text[index++]) {
                                    in '0'..'9' -> hex - '0'
                                    in 'a'..'f' -> hex - 'a' + 10
                                    in 'A'..'F' -> hex - 'A' + 10
                                    else -> error("INVALID_JSON", "Unicode escapes require ASCII hexadecimal digits.", path)
                                }
                                code = code * 16 + digit
                            }
                            code.toChar()
                        }
                        else -> error("INVALID_JSON", "Invalid string escape.", path)
                    }
                }
                if (result.length >= limits.maxStringChars) error("STRING_LIMIT", "String exceeds the decoded character limit.", path)
                result.append(c)
            }
            error("INVALID_JSON", "Unterminated JSON string.", path)
        }

        private fun number(path: JsonPath): JsonElement {
            val start = index
            take('-')
            if (!take('0')) {
                if (index >= text.length || text[index] !in '1'..'9') error("INVALID_JSON", "Invalid number.", path)
                digits()
            }
            if (take('.')) {
                if (index >= text.length || text[index] !in '0'..'9') error("INVALID_JSON", "Expected decimal digits.", path)
                digits()
            }
            if (index < text.length && text[index] in "eE") {
                index++
                if (index < text.length && text[index] in "+-") index++
                if (index >= text.length || text[index] !in '0'..'9') error("INVALID_JSON", "Expected exponent digits.", path)
                digits()
            }
            if (index - start > limits.maxNumberChars) error("NUMBER_LIMIT", "Number exceeds the lexical length limit.", path)
            return JsonUnquotedLiteral(text.substring(start, index))
        }

        private fun digits() { while (index < text.length && text[index] in '0'..'9') index++ }
        private fun literal(expected: String, path: JsonPath) {
            if (!text.startsWith(expected, index)) error("INVALID_JSON", "Invalid JSON literal.", path)
            index += expected.length
        }
        private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
        private fun take(c: Char): Boolean = if (index < text.length && text[index] == c) { index++; true } else false
        private fun expect(c: Char, path: JsonPath) { if (!take(c)) error("INVALID_JSON", "Expected '$c'.", path) }
        private fun error(code: String, message: String, path: JsonPath): Nothing =
            throw JsonFailure(Diagnostic(code, message, path.pointer(), offset = index))
    }

    private inner class Writer {
        private val output = ByteArrayOutputStream()
        private val active = IdentityHashMap<JsonElement, Boolean>()
        private var nodes = 0
        fun write(value: JsonElement): ByteArray { value(value, 1, JsonPath.ROOT); return output.toByteArray() }
        private fun value(node: JsonElement, depth: Int, path: JsonPath) {
            if (depth > limits.maxDepth) fail("DEPTH_LIMIT", "JSON exceeds the nesting limit.", path)
            if (++nodes > limits.maxNodes) fail("NODE_LIMIT", "JSON exceeds the value-count limit.", path)
            if (active.put(node, true) != null) fail("CYCLIC_JSON", "Cyclic JSON is not supported.", path)
            try {
                when (node) {
                    is JsonObject -> {
                        if (node.size > limits.maxCollectionEntries) fail("COLLECTION_LIMIT", "Object exceeds the member limit.", path)
                        ascii("{", path)
                        node.entries.forEachIndexed { index, (key, child) ->
                            if (index > 0) ascii(",", path)
                            string(key, path); ascii(":", path)
                            value(child, depth + 1, path.child(key))
                        }
                        ascii("}", path)
                    }
                    is JsonArray -> {
                        if (node.size > limits.maxCollectionEntries) fail("COLLECTION_LIMIT", "Array exceeds the entry limit.", path)
                        ascii("[", path)
                        node.forEachIndexed { index, child ->
                            if (index > 0) ascii(",", path)
                            value(child, depth + 1, path.child(index.toString()))
                        }
                        ascii("]", path)
                    }
                    is JsonPrimitive -> {
                        if (node.isString) string(node.content, path)
                        else {
                            val token = node.content
                            if (token !in setOf("true", "false", "null") &&
                                (token.length > limits.maxNumberChars || !NUMBER.matches(token))) {
                                fail("INVALID_JSON_VALUE", "Invalid or oversized JSON number/literal.", path)
                            }
                            ascii(token, path)
                        }
                    }
                }
            } finally { active.remove(node) }
        }
        private fun string(text: String, path: JsonPath) {
            if (text.length > limits.maxStringChars) fail("STRING_LIMIT", "String exceeds the decoded character limit.", path)
            checkSurrogates(text, path)
            ascii("\"", path)
            var index = 0
            while (index < text.length) {
                val c = text[index++]
                when (c) {
                    '"' -> ascii("\\\"", path)
                    '\\' -> ascii("\\\\", path)
                    '\b' -> ascii("\\b", path)
                    '\u000c' -> ascii("\\f", path)
                    '\n' -> ascii("\\n", path)
                    '\r' -> ascii("\\r", path)
                    '\t' -> ascii("\\t", path)
                    else -> when {
                        c < ' ' -> ascii("\\u" + c.code.toString(16).padStart(4, '0'), path)
                        c.isHighSurrogate() -> {
                            val codePoint = Character.toCodePoint(c, text[index++])
                            byte(0xf0 or (codePoint shr 18), path)
                            byte(0x80 or ((codePoint shr 12) and 0x3f), path)
                            byte(0x80 or ((codePoint shr 6) and 0x3f), path)
                            byte(0x80 or (codePoint and 0x3f), path)
                        }
                        c.code < 0x80 -> byte(c.code, path)
                        c.code < 0x800 -> {
                            byte(0xc0 or (c.code shr 6), path); byte(0x80 or (c.code and 0x3f), path)
                        }
                        else -> {
                            byte(0xe0 or (c.code shr 12), path)
                            byte(0x80 or ((c.code shr 6) and 0x3f), path)
                            byte(0x80 or (c.code and 0x3f), path)
                        }
                    }
                }
            }
            ascii("\"", path)
        }
        private fun ascii(text: String, path: JsonPath) { text.forEach { byte(it.code, path) } }
        private fun byte(value: Int, path: JsonPath) {
            if (output.size() >= limits.maxBytes) fail("BYTE_LIMIT", "JSON exceeds the encoded byte limit.", path)
            output.write(value)
        }
    }

    private fun checkSurrogates(text: String, path: JsonPath) {
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            if (c.isHighSurrogate()) {
                if (index >= text.length || !text[index++].isLowSurrogate()) fail("INVALID_UNICODE", "Unpaired Unicode surrogate.", path)
            } else if (c.isLowSurrogate()) fail("INVALID_UNICODE", "Unpaired Unicode surrogate.", path)
        }
    }
    private fun fail(code: String, message: String, path: JsonPath = JsonPath.ROOT): Nothing = throw JsonFailure(Diagnostic(code, message, path.pointer()))

    private companion object {
        val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}

/** Parent-linked paths avoid repeatedly copying arbitrarily large ancestor member names. */
private class JsonPath private constructor(private val parent: JsonPath?, private val token: String?) {
    fun child(token: String): JsonPath = JsonPath(this, token)
    fun pointer(): String {
        val tokens = mutableListOf<String>()
        var cursor: JsonPath? = this
        while (cursor?.token != null) {
            tokens += cursor.token!!
            cursor = cursor.parent
        }
        return tokens.asReversed().joinToString(separator = "", transform = { "/" + pointerToken(it) })
    }
    companion object { val ROOT = JsonPath(null, null) }
}
