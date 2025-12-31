package com.iosdevlog.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.WifiP2pManager.ChannelListener
import android.util.Log

class WifiDirectManager(private val context: Context, private val listener: WifiDirectListener) : ChannelListener {

    interface WifiDirectListener {
        fun onDeviceFound(device: WifiP2pDevice)
        fun onDeviceLost(device: WifiP2pDevice)
        fun onConnectionInfoAvailable(info: WifiP2pInfo)
        fun onGroupInfoAvailable(group: WifiP2pGroup?)
        fun onConnectionError(error: String)
        fun onConnectionSuccess()
        fun onDisconnected()
    }

    private val manager: WifiP2pManager by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private val channel: Channel by lazy { manager.initialize(context, context.mainLooper, this) }
    private val deviceList: MutableList<WifiP2pDevice> = mutableListOf()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        listener.onConnectionError("WiFi Direct is not enabled")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel) { peerList: WifiP2pDeviceList ->
                        updateDeviceList(peerList.deviceList)
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra(android.net.ConnectivityManager.EXTRA_NETWORK_INFO) as android.net.NetworkInfo?
                    if (networkInfo?.isConnected == true) {
                        manager.requestConnectionInfo(channel) { info ->
                            listener.onConnectionInfoAvailable(info)
                        }
                        manager.requestGroupInfo(channel) { group ->
                            listener.onGroupInfoAvailable(group)
                        }
                        listener.onConnectionSuccess()
                    } else {
                        listener.onDisconnected()
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // 设备信息变化，不需要特殊处理
                }
            }
        }
    }

    private fun updateDeviceList(newDevices: Collection<WifiP2pDevice>) {
        val oldDevices = HashSet(deviceList)
        val currentDevices = HashSet(newDevices)

        // 移除不再存在的设备
        val removedDevices = oldDevices - currentDevices
        removedDevices.forEach { device ->
            deviceList.remove(device)
            listener.onDeviceLost(device)
        }

        // 添加新发现的设备
        val addedDevices = currentDevices - oldDevices
        addedDevices.forEach { device ->
            deviceList.add(device)
            listener.onDeviceFound(device)
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        // 检查WiFi是否启用
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            listener.onConnectionError("WiFi is not enabled. Please enable WiFi first.")
            return
        }

        // 开始发现设备
        manager.discoverPeers(channel, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery started successfully")
            }

            override fun onFailure(reasonCode: Int) {
                val errorMsg = when (reasonCode) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P is unsupported on this device"
                    WifiP2pManager.ERROR -> "Discovery failed. Please try again."
                    WifiP2pManager.BUSY -> "P2P is busy. Please try again later."
                    else -> "Unknown error: $reasonCode"
                }
                listener.onConnectionError(errorMsg)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        manager.connect(channel, config, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connecting to ${device.deviceName}")
            }

            override fun onFailure(reasonCode: Int) {
                val errorMsg = when (reasonCode) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P is unsupported"
                    WifiP2pManager.ERROR -> "Error occurred"
                    WifiP2pManager.BUSY -> "P2P is busy"
                    else -> "Unknown error: $reasonCode"
                }
                listener.onConnectionError("Connection failed: $errorMsg")
            }
        })
    }

    fun disconnect() {
        manager.removeGroup(channel, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Disconnected successfully")
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Disconnect failed: $reasonCode")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun checkConnectionStatus() {
        // 检查当前连接状态
        manager.requestConnectionInfo(channel) { info ->
            if (info.groupFormed) {
                Log.d(TAG, "Already connected: ${info.groupOwnerAddress}")
                listener.onConnectionInfoAvailable(info)
                // 同时请求组信息，以便获取完整的连接状态
                manager.requestGroupInfo(channel) {
                    listener.onGroupInfoAvailable(it)
                }
                listener.onConnectionSuccess()
            }
        }
    }

    fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)
    }

    fun unregisterReceiver() {
        context.unregisterReceiver(receiver)
    }

    override fun onChannelDisconnected() {
        Log.d(TAG, "Channel disconnected")
        // 重新初始化通道
        manager.initialize(context, context.mainLooper, this)
    }

    companion object {
        private const val TAG = "WifiDirectManager"
    }
}
