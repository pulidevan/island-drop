package com.puli.islanddrop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.InetAddress

/** Browses for the Mac app advertising `_islanddrop-mac._tcp` over Bonjour/mDNS. */
class MacFinder(private val c: Context, private val onChange: (List<NsdServiceInfo>) -> Unit) {

    private val nsd = c.getSystemService(NsdManager::class.java)
    private val found = LinkedHashMap<String, NsdServiceInfo>()
    private var lock: WifiManager.MulticastLock? = null
    private var active = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(service: NsdServiceInfo) {
            found[service.serviceName] = service
            onChange(found.values.toList())
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            found.remove(service.serviceName)
            onChange(found.values.toList())
        }
    }

    fun start() {
        if (active) return
        active = true
        val multicastLock = (c.applicationContext.getSystemService(WifiManager::class.java))
            .createMulticastLock("islanddrop-find")
        multicastLock.setReferenceCounted(false)
        multicastLock.acquire()
        lock = multicastLock
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { nsd.stopServiceDiscovery(listener) }
        runCatching { lock?.release() }
    }

    fun resolve(s: NsdServiceInfo, done: (InetAddress?, Int) -> Unit) {
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onServiceResolved(r: NsdServiceInfo) {
                Transfer.main.post { done(r.host, r.port) }
            }

            override fun onResolveFailed(r: NsdServiceInfo, code: Int) {
                Transfer.main.post { done(null, 0) }
            }
        })
    }

    companion object {
        const val SERVICE_TYPE = "_islanddrop-mac._tcp"
    }
}
