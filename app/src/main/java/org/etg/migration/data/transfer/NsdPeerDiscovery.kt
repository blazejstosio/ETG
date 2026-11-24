package org.etg.migration.data.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredPeer(
    val serviceName: String,
    val host: String,
    val port: Int
)

class NsdPeerDiscovery(context: Context) {
    companion object {
        const val SERVICE_TYPE = "_etg-transfer._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveInFlight = AtomicBoolean(false)
    private val pendingResolveQueue = ArrayDeque<NsdServiceInfo>()

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    fun startAdvertising(port: Int, deviceLabel: String = Build.MODEL) {
        stopAdvertising()
        val mgr = nsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "ETG-${deviceLabel.replace(Regex("[^a-zA-Z0-9_-]"), "_")}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stopAdvertising() {
        val mgr = nsdManager ?: return
        val listener = registrationListener ?: return
        registrationListener = null
        runCatching { mgr.unregisterService(listener) }
    }

    fun startDiscovery() {
        stopDiscovery()
        _peers.value = emptyList()
        val mgr = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE &&
                    !serviceInfo.serviceType.contains("_etg-transfer")
                ) {
                    return
                }
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.serviceName == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stopDiscovery() {
        val mgr = nsdManager ?: return
        val listener = discoveryListener ?: return
        discoveryListener = null
        synchronized(pendingResolveQueue) {
            pendingResolveQueue.clear()
        }
        resolveInFlight.set(false)
        runCatching { mgr.stopServiceDiscovery(listener) }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        synchronized(pendingResolveQueue) {
            pendingResolveQueue.addLast(serviceInfo)
        }
        drainResolveQueue()
    }

    @Suppress("DEPRECATION")
    private fun drainResolveQueue() {
        val mgr = nsdManager ?: return
        if (!resolveInFlight.compareAndSet(false, true)) return
        val next = synchronized(pendingResolveQueue) {
            if (pendingResolveQueue.isEmpty()) null else pendingResolveQueue.removeFirst()
        }
        if (next == null) {
            resolveInFlight.set(false)
            return
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolveInFlight.set(false)
                drainResolveQueue()
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress
                val port = resolved.port
                if (!host.isNullOrBlank() && port > 0) {
                    val peer = DiscoveredPeer(resolved.serviceName, host, port)
                    val current = _peers.value.filterNot { it.serviceName == peer.serviceName }
                    _peers.value = current + peer
                }
                resolveInFlight.set(false)
                drainResolveQueue()
            }
        }
        runCatching {
            mgr.resolveService(next, resolveListener)
        }.onFailure {
            resolveInFlight.set(false)
            drainResolveQueue()
        }
    }
}
