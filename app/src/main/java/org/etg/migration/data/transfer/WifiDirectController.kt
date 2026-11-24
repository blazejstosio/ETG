package org.etg.migration.data.transfer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.etg.migration.data.PermissionHelper

data class WifiDirectState(
    val isSupported: Boolean = true,
    val groupActive: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerIp: String? = null,
    val peers: List<WifiP2pDevice> = emptyList(),
    val statusText: String = ""
)

class WifiDirectController(private val context: Context) {
    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val _state = MutableStateFlow(WifiDirectState(isSupported = manager != null))
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            val mgr = manager ?: return
            val ch = channel ?: return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (PermissionHelper.hasNearbyWifi(context)) {
                        mgr.requestPeers(ch) { peerList ->
                            _state.value = _state.value.copy(
                                peers = peerList.deviceList.toList()
                            )
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    mgr.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                        if (info != null && info.groupFormed) {
                            _state.value = _state.value.copy(
                                groupActive = true,
                                isGroupOwner = info.isGroupOwner,
                                groupOwnerIp = info.groupOwnerAddress?.hostAddress,
                                statusText = if (info.isGroupOwner) {
                                    "Wi-Fi Direct Group Owner active"
                                } else {
                                    "Connected to P2P Host ${info.groupOwnerAddress?.hostAddress.orEmpty()}"
                                }
                            )
                        } else {
                            _state.value = _state.value.copy(
                                groupActive = false,
                                isGroupOwner = false,
                                groupOwnerIp = null
                            )
                        }
                    }
                }
            }
        }
    }

    fun register() {
        val mgr = manager ?: return
        if (channel == null) {
            channel = mgr.initialize(context, context.mainLooper, null)
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
    }

    fun unregister() {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    fun createGroup() {
        val mgr = manager ?: return
        register()
        val ch = channel ?: return
        if (!PermissionHelper.hasNearbyWifi(context)) {
            _state.value = _state.value.copy(statusText = "Nearby Wi-Fi permission required")
            return
        }
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(
                    groupActive = true,
                    isGroupOwner = true,
                    statusText = "Wi-Fi Direct Group created (192.168.49.1)"
                )
            }

            override fun onFailure(reason: Int) {
                _state.value = _state.value.copy(
                    statusText = "Wi-Fi Direct group creation failed (code $reason)"
                )
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        val mgr = manager ?: return
        register()
        val ch = channel ?: return
        if (!PermissionHelper.hasNearbyWifi(context)) {
            _state.value = _state.value.copy(statusText = "Nearby Wi-Fi permission required")
            return
        }
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(statusText = "Scanning Wi-Fi Direct peers...")
            }

            override fun onFailure(reason: Int) {
                _state.value = _state.value.copy(statusText = "Peer discovery failed (code $reason)")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String) {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (!PermissionHelper.hasNearbyWifi(context)) return
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
        }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(statusText = "Connecting to $deviceAddress...")
            }

            override fun onFailure(reason: Int) {
                _state.value = _state.value.copy(statusText = "P2P connect failed (code $reason)")
            }
        })
    }

    fun removeGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _state.value = _state.value.copy(
                        groupActive = false,
                        isGroupOwner = false,
                        groupOwnerIp = null,
                        statusText = "Wi-Fi Direct stopped"
                    )
                }

                override fun onFailure(reason: Int) = Unit
            })
        }
    }
}
