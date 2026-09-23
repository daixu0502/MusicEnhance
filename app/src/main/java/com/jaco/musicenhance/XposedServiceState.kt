package com.jaco.musicenhance

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object XposedServiceState {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    var service by mutableStateOf<XposedService?>(null)
        private set

    var prefs by mutableStateOf<SharedPreferences?>(null)
        private set

    val isConnected: Boolean
        get() = service != null && prefs != null

    fun ensureRegistered() {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                runCatching { service.getRemotePreferences(Prefs.NAME) }
                    .onSuccess { remotePrefs ->
                        mainHandler.post {
                            this@XposedServiceState.service = service
                            prefs = remotePrefs
                        }
                    }
                    .onFailure { mainHandler.post(::clear) }
            }

            override fun onServiceDied(service: XposedService) {
                mainHandler.post(::clear)
            }
        })
    }

    private fun clear() {
        service = null
        prefs = null
    }
}
