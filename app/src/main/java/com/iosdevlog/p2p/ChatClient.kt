package com.iosdevlog.p2p

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class ChatClient(private val listener: ChatClientListener) {

    interface ChatClientListener {
        fun onConnected()
        fun onChatDisconnected()
        fun onMessageReceived(message: String)
        fun onError(error: String)
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "ChatClient"
        private const val SERVER_IP = "192.168.49.1"
        private const val SERVER_PORT = 8080
    }

    fun connect() {
        scope.launch {
            try {
                socket = Socket(SERVER_IP, SERVER_PORT)
                writer = PrintWriter(socket?.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                isConnected = true

                withContext(Dispatchers.Main) {
                    listener.onConnected()
                }

                // 开始接收消息
                receiveMessages()
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    listener.onError("Connection failed: ${e.message}")
                }
                disconnect()
            }
        }
    }

    private suspend fun receiveMessages() {
        try {
            Log.d(TAG, "Started receiving messages")
            var message: String?
            while (socket?.isConnected == true && isConnected) {
                // 使用read()方法逐字符读取，避免readLine()阻塞问题
                val buffer = CharArray(1024)
                val bytesRead = reader?.read(buffer)
                if (bytesRead != null && bytesRead > 0) {
                    val receivedMessage = String(buffer, 0, bytesRead).trim()
                    if (receivedMessage.isNotEmpty()) {
                        Log.d(TAG, "Message received: $receivedMessage")
                        withContext(Dispatchers.Main) {
                            listener.onMessageReceived(receivedMessage)
                        }
                    }
                } else if (bytesRead == -1) {
                    // 流已关闭
                    Log.d(TAG, "Stream closed, stopping message reception")
                    break
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error receiving message: ${e.message}")
            withContext(Dispatchers.Main) {
                listener.onError("Error receiving message: ${e.message}")
            }
        } finally {
            Log.d(TAG, "Stopped receiving messages")
            disconnect()
        }
    }

    fun sendMessage(message: String) {
        scope.launch {
            try {
                if (isConnected && writer != null) {
                    writer?.println(message)
                    writer?.flush()
                } else {
                    withContext(Dispatchers.Main) {
                        listener.onError("Not connected to server")
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    listener.onError("Failed to send message: ${e.message}")
                }
                disconnect()
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                isConnected = false
                reader?.close()
                writer?.close()
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error disconnecting: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    listener.onChatDisconnected()
                }
            }
        }
    }

    fun isConnected(): Boolean {
        return isConnected && socket?.isConnected == true
    }
}
