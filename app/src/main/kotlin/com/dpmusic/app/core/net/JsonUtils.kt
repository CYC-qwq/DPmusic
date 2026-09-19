package com.dpmusic.app.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 全局 JSON 实例：三平台接口字段多变，全部采用宽松解析 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * 从原始响应中剥离 JSONP 包裹并解析为 JsonElement。
 * 兼容 `callback({...})` / `MusicJsonCallback({...})` 等回调格式与前后杂质文本。
 */
fun parseJsonPayload(raw: String): JsonElement {
    val text = raw.trim()
    val objStart = text.indexOf('{')
    val arrStart = text.indexOf('[')
    val start = when {
        objStart < 0 -> arrStart
        arrStart < 0 -> objStart
        else -> minOf(objStart, arrStart)
    }
    if (start < 0) throw IllegalArgumentException("响应不是 JSON：${text.take(80)}")
    val end = maxOf(text.lastIndexOf('}'), text.lastIndexOf(']'))
    if (end <= start) throw IllegalArgumentException("响应不是 JSON：${text.take(80)}")
    return AppJson.parseToJsonElement(text.substring(start, end + 1))
}

/* ---------- JsonElement 访问助手：宽容取字段，缺失一律返回 null ---------- */

fun JsonElement?.objOrNull(key: String): JsonObject? =
    ((this as? JsonObject)?.get(key)) as? JsonObject

fun JsonElement?.arrOrNull(key: String): JsonArray? =
    ((this as? JsonObject)?.get(key)) as? JsonArray

fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

fun JsonElement?.long(key: String): Long? {
    val content = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull ?: return null
    return content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong()
}

fun JsonElement?.int(key: String): Int? = this.long(key)?.toInt()

fun JsonElement?.bool(key: String): Boolean? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull

/** 数组元素列表（安全空数组） */
fun JsonArray?.objList(): List<JsonElement> = this?.toList() ?: emptyList()

/** 字符串数组字段 → List<String>（过滤 null / 空白项） */
fun JsonElement?.strList(key: String): List<String> =
    arrOrNull(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()