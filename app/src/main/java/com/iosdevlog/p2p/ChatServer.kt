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
import java.net.ServerSocket
import java.net.Socket

class ChatServer(private val port: Int = 8080, private val listener: ChatServerListener) {

    interface ChatServerListener {
        fun onServerStarted()
        fun onServerStopped()
        fun onClientConnected(clientSocket: Socket)
        fun onClientDisconnected(clientSocket: Socket)
        fun onServerMessageReceived(clientSocket: Socket, message: String)
        fun onServerError(error: String)
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = mutableListOf<Socket>()
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "ChatServer"
    }

    fun start() {
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                
                withContext(Dispatchers.Main) {
                    listener.onServerStarted()
                }
                
                Log.d(TAG, "Server started on port $port")
                
                // 等待客户端连接
                while (isRunning && !serverSocket?.isClosed!!) {
                    val clientSocket = serverSocket?.accept()
                    if (clientSocket != null) {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: IOException) {
                if (isRunning) {
                    Log.e(TAG, "Server error: ${e.message}")
                    withContext(Dispatchers.Main) {
                    listener.onServerError("Server error: ${e.message}")
                }
                }
            } finally {
                stop()
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        scope.launch {
            try {
                clients.add(clientSocket)
                withContext(Dispatchers.Main) {
                    listener.onClientConnected(clientSocket)
                }
                
                Log.d(TAG, "Client connected: ${clientSocket.inetAddress.hostAddress}")
                
                val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
                var message: String? = null
                
                while (isRunning && clientSocket.isConnected && reader.readLine().also { message = it } != null) {
                    if (message != null && message!!.isNotEmpty()) {
                        Log.d(TAG, "Message received from ${clientSocket.inetAddress.hostAddress}: $message")
                        withContext(Dispatchers.Main) {
                            listener.onServerMessageReceived(clientSocket, message!!)
                        }
                        // 广播消息给除发送者外的所有客户端
                        broadcastMessage(clientSocket, message!!)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Client error: ${e.message}")
            } finally {
                clients.remove(clientSocket)
                clientSocket.close()
                withContext(Dispatchers.Main) {
                    listener.onClientDisconnected(clientSocket)
                }
                Log.d(TAG, "Client disconnected: ${clientSocket.inetAddress.hostAddress}")
            }
        }
    }

    private fun broadcastMessage(senderSocket: Socket, message: String) {
        clients.forEach { client ->
            // 不向发送者本身发送消息
            if (client != senderSocket) {
                scope.launch {
                    try {
                        val writer = PrintWriter(client.outputStream, true)
                        writer.println(message)
                        writer.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to send message to client: ${e.message}")
                    }
                }
            }
        }
    }

    fun sendMessage(clientSocket: Socket, message: String) {
        scope.launch {
            try {
                val writer = PrintWriter(clientSocket.outputStream, true)
                writer.println(message)
                writer.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send message: ${e.message}")
            }
        }
    }
    
    // 广播消息给所有客户端（用于组所有者发送消息）
    fun broadcastToAllClients(message: String) {
        scope.launch {
            clients.forEach { client ->
                try {
                    val writer = PrintWriter(client.outputStream, true)
                    writer.println(message)
                    writer.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to send message to client: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        clients.forEach { client ->
            try {
                client.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing client: ${e.message}")
            }
        }
        clients.clear()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        }
        // 直接在IO线程中调用监听器，不需要切换到Main线程
        listener.onServerStopped()
        Log.d(TAG, "Server stopped")
    }

    fun isRunning(): Boolean {
        return isRunning
    }
}