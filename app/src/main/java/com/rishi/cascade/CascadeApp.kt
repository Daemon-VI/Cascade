package com.rishi.cascade

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.rishi.cascade.trigger.ScreenReceiver
import com.rishi.cascade.trigger.Scheduler
import com.rishi.cascade.trigger.TriggerEngine
import com.rishi.cascade.store.FlowStore

class CascadeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        FlowStore.get(this)
        ScreenReceiver.register(this)
        Scheduler.rescheduleAll(this)
        watchNetwork()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var wasWifi = false

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    if (isWifi && !wasWifi) {
                        wasWifi = true
                        TriggerEngine.fire(this@CascadeApp, "wifi_connected", mapOf("ssid" to ssid()))
                    } else if (!isWifi && wasWifi) {
                        wasWifi = false
                        TriggerEngine.fire(this@CascadeApp, "wifi_disconnected")
                    }
                }

                override fun onLost(network: Network) {
                    if (wasWifi) {
                        wasWifi = false
                        TriggerEngine.fire(this@CascadeApp, "wifi_disconnected")
                    }
                }
            })
        } catch (e: Exception) { }
    }

    private fun ssid(): String = try {
        @Suppress("DEPRECATION")
        val info = applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        @Suppress("DEPRECATION")
        info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: ""
    } catch (e: Exception) { "" }
}
