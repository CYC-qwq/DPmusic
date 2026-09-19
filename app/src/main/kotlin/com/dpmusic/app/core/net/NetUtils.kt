package com.dpmusic.app.core.net

import java.net.URLEncoder

/** URL 编码（UTF-8） */
fun urlEnc(value: String): String = URLEncoder.encode(value, "UTF-8")

/** 将 http:// 升级为 https://（各平台 CDN 均已支持，规避明文流量限制） */
fun String.toHttps(): String =
    if (startsWith("http://")) "https://" + removePrefix("http://") else this