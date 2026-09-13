package com.wirelessmonitor

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

object NsdHelper {

    private const val SERVICE_TYPE = "_wirelessmonitor._tcp."
    private const val SERVICE_NAME = "WirelessMonitorPad"

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun registerService(context: Context, port: Int) {

        val nsdManager =
            context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        registrationListener = listener

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    fun unregisterService(context: Context) {
        try {
            val nsdManager =
                context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (_: Exception) {
        }
        registrationListener = null
    }

    fun discoverService(
        context: Context,
        onFound: (host: String, port: Int) -> Unit,
        onFailure: () -> Unit
    ) {

        val nsdManager =
            context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

        var finished = false

        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {

                if (!service.serviceName.contains(SERVICE_NAME)) return

                nsdManager.resolveService(service, object : NsdManager.ResolveListener {

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        if (!finished) {
                            finished = true
                            stopSafely(nsdManager)
                            onFailure()
                        }
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (finished) return

                        val host = info.host?.hostAddress

                        if (host == null) {
                            finished = true
                            stopSafely(nsdManager)
                            onFailure()
                            return
                        }

                        finished = true
                        stopSafely(nsdManager)
                        onFound(host, info.port)
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (!finished) {
                    finished = true
                    onFailure()
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        discoveryListener = listener

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            if (!finished) {
                finished = true
                onFailure()
            }
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!finished) {
                finished = true
                stopSafely(nsdManager)
                onFailure()
            }
        }, 6000)
    }

    private fun stopSafely(nsdManager: NsdManager) {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        }
        discoveryListener = null
    }
}
