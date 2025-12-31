package com.iosdevlog.p2p

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.iosdevlog.p2p.ui.theme.P2PTheme

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity(), WifiDirectManager.WifiDirectListener, ChatClient.ChatClientListener {

    private lateinit var wifiDirectManager: WifiDirectManager
    private lateinit var chatClient: ChatClient
    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.INTERNET
    )

    // 状态管理
    private val devices = mutableStateListOf<WifiP2pDevice>()
    private val messages = mutableStateListOf<String>()
    private var isConnected by mutableStateOf(false)
    private var isChatConnected by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("Disconnected")
    private var messageInput by mutableStateOf("")
    private var isDiscovering by mutableStateOf(false)
    private var currentIpAddress by mutableStateOf("Unknown IP")

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allGranted = granted.all { it.value }
        if (allGranted) {
            startWifiDirect()
        } else {
            Log.e("MainActivity", "Permissions not granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        wifiDirectManager = WifiDirectManager(this, this)
        chatClient = ChatClient(this)
        
        // 获取当前IP地址
        currentIpAddress = fetchCurrentIpAddress()
        
        setContent {
            P2PTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("WiFi Direct Chat") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                titleContentColor = Color.White
                            )
                        )
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(it)
                            .padding(16.dp)
                    ) {
                        // 连接状态和IP地址
                        Text(
                            text = "Connection Status: $connectionStatus | IP: $currentIpAddress",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // 主要内容区域
                        if (isConnected && isChatConnected) {
                            // 聊天界面
                            ChatScreen(
                                messages = messages,
                                messageInput = messageInput,
                                onMessageInputChange = { messageInput = it },
                                onSendMessage = { sendMessage(it) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            // 设备列表界面
                            DeviceListScreen(
                                devices = devices,
                                isDiscovering = isDiscovering,
                                onStartDiscovery = { startDiscovery() },
                                onConnect = { connectToDevice(it) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
        
        // 请求权限
        requestPermissions()
    }

    private fun requestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            startWifiDirect()
        }
    }

    private fun startWifiDirect() {
        wifiDirectManager.registerReceiver()
        // 检查当前连接状态，如果已经连接则直接进入聊天
        wifiDirectManager.checkConnectionStatus()
        // 开始设备发现，以便用户可以连接其他设备
        startDiscovery()
    }

    private fun startDiscovery() {
        isDiscovering = true
        wifiDirectManager.startDiscovery()
    }

    private fun connectToDevice(device: WifiP2pDevice) {
        wifiDirectManager.connect(device)
    }

    private fun sendMessage(message: String) {
        if (message.isNotEmpty()) {
            messages.add("Me: $message")
            chatClient.sendMessage(message)
            messageInput = ""
        }
    }

    // WifiDirectListener 实现
    override fun onDeviceFound(device: WifiP2pDevice) {
        devices.add(device)
        Log.d("MainActivity", "Device found: ${device.deviceName}")
    }

    override fun onDeviceLost(device: WifiP2pDevice) {
        devices.remove(device)
        Log.d("MainActivity", "Device lost: ${device.deviceName}")
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        Log.d("MainActivity", "Connection info available: ${info.groupOwnerAddress}")
        // 连接成功后，启动聊天客户端
        if (!isChatConnected) {
            chatClient.connect()
        }
    }

    override fun onGroupInfoAvailable(group: android.net.wifi.p2p.WifiP2pGroup?) {
        // 从WiFi Direct组中获取当前设备的IP地址
        if (group != null) {
            Log.d("MainActivity", "Group info available: ${group.networkName}")
            // 延迟获取IP地址，确保设备已经获取到DHCP分配的地址
            Thread.sleep(1000) // 等待1秒
            // 获取当前设备的WiFi Direct IP地址
            val wifiDirectIp = getWifiDirectDeviceIp()
            currentIpAddress = wifiDirectIp
            Log.d("MainActivity", "WiFi Direct Device IP: $wifiDirectIp")
        }
    }

    override fun onConnectionError(error: String) {
        connectionStatus = "Error: $error"
        Log.e("MainActivity", "Connection error: $error")
        isDiscovering = false
    }

    override fun onConnectionSuccess() {
        connectionStatus = "Connected"
        isConnected = true
        Log.d("MainActivity", "Connection successful")
        isDiscovering = false
    }

    override fun onDisconnected() {
        connectionStatus = "Disconnected"
        isConnected = false
        isChatConnected = false
        chatClient.disconnect()
        Log.d("MainActivity", "Disconnected")
    }

    // ChatClientListener 实现
    override fun onConnected() {
        isChatConnected = true
        messages.add("System: Connected to chat server")
        Log.d("MainActivity", "Chat connected")
    }

    override fun onChatDisconnected() {
        isChatConnected = false
        messages.add("System: Disconnected from chat server")
        Log.d("MainActivity", "Chat disconnected")
    }

    override fun onMessageReceived(message: String) {
        messages.add("Server: $message")
        Log.d("MainActivity", "Message received: $message")
    }

    override fun onError(error: String) {
        messages.add("System: $error")
        Log.e("MainActivity", "Chat error: $error")
    }

    // 获取当前IP地址
    private fun fetchCurrentIpAddress(): String {
        try {
            val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface: NetworkInterface = interfaces.nextElement()
                val addresses: Enumeration<InetAddress> = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address: InetAddress = addresses.nextElement()
                    val hostAddress = address.hostAddress
                    if (!address.isLoopbackAddress && hostAddress != null && hostAddress.indexOf(":") < 0) {
                        // 返回IPv4地址，排除IPv6和回环地址
                        return hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting IP address: ${e.message}")
        }
        return "Unknown IP"
    }

    // 获取WiFi Direct设备的实际IP地址
    private fun getWifiDirectDeviceIp(): String {
        try {
            val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            Log.d("MainActivity", "Listing all network interfaces:")
            while (interfaces.hasMoreElements()) {
                val networkInterface: NetworkInterface = interfaces.nextElement()
                val interfaceName = networkInterface.name
                val displayName = networkInterface.displayName
                val isUp = networkInterface.isUp
                Log.d("MainActivity", "Interface: $interfaceName ($displayName), isUp: $isUp")
                
                // 只检查活动的网络接口
                if (isUp) {
                    val addresses: Enumeration<InetAddress> = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address: InetAddress = addresses.nextElement()
                        val hostAddress = address.hostAddress
                        val isLoopback = address.isLoopbackAddress
                        Log.d("MainActivity", "  Address: $hostAddress, isLoopback: $isLoopback")
                        if (!isLoopback && hostAddress != null && hostAddress.indexOf(":") < 0) {
                            // 返回非回环的IPv4地址
                            // 检查是否在192.168.49.x网段，且不是组所有者地址(192.168.49.1)
                            if (hostAddress.startsWith("192.168.49.") && hostAddress != "192.168.49.1") {
                                Log.d("MainActivity", "Found WiFi Direct IP: $hostAddress")
                                return hostAddress
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting WiFi Direct IP address: ${e.message}")
        }
        return "Unknown WiFi Direct IP"
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiDirectManager.unregisterReceiver()
        wifiDirectManager.disconnect()
        chatClient.disconnect()
    }
}

@Composable
fun DeviceListScreen(
    devices: List<WifiP2pDevice>,
    isDiscovering: Boolean,
    onStartDiscovery: () -> Unit,
    onConnect: (WifiP2pDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = onStartDiscovery,
                enabled = !isDiscovering,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(if (isDiscovering) "Discovering..." else "Discover Devices")
            }
        }
        
        if (devices.isEmpty()) {
            Text(text = "No devices found. Click 'Discover Devices' to start scanning.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(devices) {
                    DeviceItem(device = it, onConnect = onConnect)
                }
            }
        }
    }
}

@Composable
fun DeviceItem(device: WifiP2pDevice, onConnect: (WifiP2pDevice) -> Unit) {
    val deviceStatus = when (device.status) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = device.deviceName ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
            Text(text = device.deviceAddress, style = MaterialTheme.typography.bodySmall)
            Text(text = "Status: $deviceStatus", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onConnect(device) },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Connect")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<String>,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 消息列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = false
        ) {
            items(messages) {
                MessageItem(message = it)
            }
        }
        
        // 消息输入区域
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = messageInput,
                onValueChange = onMessageInputChange,
                placeholder = { Text("Type a message...") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Button(
                onClick = { onSendMessage(messageInput) },
                modifier = Modifier.wrapContentHeight()
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun MessageItem(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
