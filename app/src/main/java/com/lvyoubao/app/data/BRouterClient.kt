package com.lvyoubao.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import btools.routingapp.IBRouterService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * BRouter 联动（离线高程路由）：通过 AIDL 调用用户已安装的 BRouter 应用。
 * 未安装或绑定/路由超时抛 ApiException，由上层降级到高德路线或提示。
 */
object BRouterClient {

    private const val TARGET_PACKAGE = "btools.routingapp"
    private const val SERVICE_ACTION = "btools.routingapp.IRouterService"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
    }.isSuccess

    suspend fun walkingRoute(context: Context, fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): AmapClient.RouteResult =
        suspendCoroutine { cont ->
            var finished = false
            val done = CountDownLatch(1)
            val connRef = arrayOfNulls<ServiceConnection>(1)

            fun finish(result: Result<String>) {
                if (finished) return
                finished = true
                done.countDown()
                result.fold(
                    onSuccess = { gpx ->
                        if (cont.isActive) cont.resume(AmapClient.parseGpx(gpx))
                    },
                    onFailure = { e ->
                        if (cont.isActive) cont.resumeWithException(
                            if (e is ApiException) e else ApiException(e.message ?: "BRouter 路由失败"),
                        )
                    },
                )
            }

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    try {
                        val router = IBRouterService.Stub.asInterface(service)
                        val params = Bundle().apply {
                            putDoubleArray("lats", doubleArrayOf(fromLat, toLat))
                            putDoubleArray("lons", doubleArrayOf(fromLon, toLon))
                            putString("v", "foot")
                            putString("trackFormat", "gpx")
                            putString("maxRunningTime", "30")
                            putString("engineMode", "2") // 含高程
                        }
                        val gpx = router.getTrackFromParams(params)
                        if (gpx.isNullOrBlank()) finish(Result.failure(ApiException("BRouter 没有给出路线")))
                        else finish(Result.success(gpx))
                    } catch (e: Exception) {
                        finish(Result.failure(ApiException("BRouter 调用失败：${e.message}")))
                    } finally {
                        connRef[0]?.let { runCatching { context.unbindService(it) } }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            connRef[0] = conn

            try {
                val bound = context.bindService(
                    Intent(SERVICE_ACTION).setPackage(TARGET_PACKAGE),
                    conn,
                    Context.BIND_AUTO_CREATE,
                )
                if (!bound) finish(Result.failure(ApiException("无法连接 BRouter 服务")))
            } catch (e: Exception) {
                finish(Result.failure(ApiException("BRouter 未安装或不可用")))
            }

            // 绑定/路由超时保护
            java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                if (done.await(40, TimeUnit.SECONDS)) return@execute
                finish(Result.failure(ApiException("BRouter 路由超时")))
                connRef[0]?.let { runCatching { context.unbindService(it) } }
            }
        }
}
