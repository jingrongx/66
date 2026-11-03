package com.lvyoubao.app.data

/**
 * 会话消息附带的地图卡数据：AI 调「查路线 / 搜地点」工具时由工具层捕获，
 * 随 SessionMessage 持久化，会话流里直接渲染成可交互的地图卡。
 * points 统一存 [经度, 纬度]，与 AmapClient.RouteResult 的 (lon, lat) 顺序一致。
 */
@kotlinx.serialization.Serializable
data class MapCardData(
    val kind: String = "route",          // route=路线卡 / place=地点卡
    val title: String = "",              // 目的地名称或地点关键词
    val fromLat: Double = 0.0,
    val fromLon: Double = 0.0,
    val toLat: Double = 0.0,
    val toLon: Double = 0.0,
    val distanceM: Int = 0,
    val durationMin: Int = 0,
    val points: List<List<Double>> = emptyList(),  // 路线折线 [经度, 纬度]
    val steps: List<String> = emptyList(),          // 分步指引（纯文本）
    val pois: List<MapPoi> = emptyList(),           // 地点卡的候选点
) {
    val hasOrigin: Boolean get() = fromLat != 0.0 || fromLon != 0.0
    val hasDestination: Boolean get() = toLat != 0.0 || toLon != 0.0

    companion object {
        fun distanceText(m: Int): String =
            if (m >= 1000) String.format(java.util.Locale.CHINA, "%.1f 公里", m / 1000.0) else "$m 米"

        fun durationText(min: Int): String = when {
            min <= 0 -> "时间未知"
            min >= 60 -> "${min / 60} 小时" + (if (min % 60 > 0) " ${min % 60} 分" else "")
            else -> "约 $min 分钟"
        }
    }
}

/** 地点卡里的候选点 */
@kotlinx.serialization.Serializable
data class MapPoi(
    val name: String = "",
    val address: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val distanceM: Int = 0,
)

/** 出行方式（地图卡上的切换按钮） */
enum class RouteMode(val label: String) {
    WALK("步行"), RIDE("骑行"), DRIVE("驾车"), BUS("公交");
}

/** 一次路线规划的结果（内存态，不随会话持久化） */
data class RoutePlan(
    val mode: RouteMode,
    val distanceM: Int,
    val durationMin: Int,
    val points: List<Pair<Double, Double>>,   // (经度, 纬度)
    val steps: List<String> = emptyList(),
    val stepStarts: List<Int> = emptyList(),  // 每条指引对应的折线顶点起点下标（导航语音播报用）
    val tag: String = "",
)
