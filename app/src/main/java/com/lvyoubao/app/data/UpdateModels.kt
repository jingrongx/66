package com.lvyoubao.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ReleaseAsset(
    val name: String = "",
    val size: Long = 0,
    val browser_download_url: String = "",
)

@Serializable
data class ReleaseInfo(
    val tag_name: String = "",
    val name: String? = null,
    val body: String? = null,
    val published_at: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val apkAsset: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    val versionLabel: String
        get() = Versions.normalize(tag_name)
}

object Versions {

    /** "v0.1.0-b42" → "0.1.0" */
    fun normalize(tag: String): String =
        tag.trim().removePrefix("v").substringBefore('-').trim()

    /** 按三段数字比较；与当前版本相同或更旧返回 false */
    fun isNewer(latestTag: String, current: String): Boolean {
        val latest = normalize(latestTag).split('.').map { it.toIntOrNull() ?: 0 }
        val cur = normalize(current).split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0..2) {
            val l = latest.getOrElse(i) { 0 }
            val c = cur.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}
