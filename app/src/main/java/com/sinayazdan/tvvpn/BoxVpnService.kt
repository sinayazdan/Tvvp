package com.sinayazdan.tvvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.KeyStore
import kotlin.concurrent.thread
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

class BoxVpnService : VpnService(), PlatformInterface {

    enum class State { Stopped, Starting, Started, Error }

    companion object {
        const val ACTION_START = "com.sinayazdan.tvvpn.action.START"
        const val ACTION_STOP = "com.sinayazdan.tvvpn.action.STOP"
        private const val TAG = "SinaTV"
        private const val CHANNEL_ID = "sinatv_vpn"
        private const val NOTIFICATION_ID = 100

        @Volatile
        var state: State = State.Stopped
            private set

        @Volatile
        var activeProfileName: String = ""
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var stateListener: ((State) -> Unit)? = null

        private fun updateState(newState: State) {
            state = newState
            stateListener?.invoke(newState)
        }
    }

    private var boxService: BoxService? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultInterfaceListener: InterfaceUpdateListener? = null

    // ---------------- VPN service lifecycle ----------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBox()
            ACTION_START, null -> {
                if (state == State.Starting || state == State.Started) return START_NOT_STICKY
                startBox()
            }
        }
        return START_NOT_STICKY
    }

    private fun startBox() {
        val profile = ProfileStore.selected(this)
        if (profile == null) {
            lastError = "کانفیگی انتخاب نشده"
            updateState(State.Error)
            stopSelf()
            return
        }
        activeProfileName = profile.name
        updateState(State.Starting)
        startForegroundNotification("در حال اتصال به ${profile.name}…")
        thread(name = "sinatv-box") {
            try {
                val config = SingboxConfig.build(profile)
                val service = Libbox.newService(config, this)
                service.start()
                boxService = service
                updateState(State.Started)
                startForegroundNotification("متصل — ${profile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "failed to start box", e)
                lastError = e.message ?: "خطای ناشناخته"
                updateState(State.Error)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopBox() {
        thread(name = "sinatv-stop") {
            runCatching { boxService?.close() }
            boxService = null
            runCatching { tunDescriptor?.close() }
            tunDescriptor = null
            updateState(State.Stopped)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopBox()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { boxService?.close() }
        boxService = null
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        if (state != State.Error) updateState(State.Stopped)
        super.onDestroy()
    }

    private fun startForegroundNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sina TV VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------- PlatformInterface ----------------

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) {
            throw IllegalStateException("android: missing vpn permission")
        }
        val builder = Builder()
            .setSession(activeProfileName)
            .setMtu(options.mtu)

        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val prefix = inet4.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }
        val inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val prefix = inet6.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        if (options.autoRoute) {
            var hasRoute4 = false
            val routes4 = options.inet4RouteAddress
            while (routes4.hasNext()) {
                val prefix = routes4.next()
                builder.addRoute(prefix.address(), prefix.prefix())
                hasRoute4 = true
            }
            if (!hasRoute4) builder.addRoute("0.0.0.0", 0)

            var hasRoute6 = false
            val routes6 = options.inet6RouteAddress
            while (routes6.hasNext()) {
                val prefix = routes6.next()
                builder.addRoute(prefix.address(), prefix.prefix())
                hasRoute6 = true
            }

            runCatching { options.dnsServerAddress }.getOrNull()?.value
                ?.takeIf { it.isNotBlank() }
                ?.let { builder.addDnsServer(it) }

            val include = options.includePackage
            while (include.hasNext()) {
                runCatching { builder.addAllowedApplication(include.next()) }
            }
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                runCatching { builder.addDisallowedApplication(exclude.next()) }
            }
        }

        val descriptor = builder.establish() ?: throw IllegalStateException("android: failed to establish tun")
        tunDescriptor = descriptor
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("android: connection owner requires API 29")
        }
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == android.os.Process.INVALID_UID) {
            throw IllegalStateException("android: connection owner not found")
        }
        return uid
    }

    override fun packageNameByUid(uid: Int): String {
        val packages = packageManager.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) throw IllegalStateException("android: package not found for uid $uid")
        return packages[0]
    }

    override fun uidByPackageName(packageName: String): Int {
        return packageManager.getApplicationInfo(packageName, 0).uid
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultInterfaceListener = listener
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyDefaultInterface()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                notifyDefaultInterface()

            override fun onLost(network: Network) = notifyDefaultInterface()
        }
        defaultNetworkCallback = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        notifyDefaultInterface()
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultNetworkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
            }
        }
        defaultNetworkCallback = null
        defaultInterfaceListener = null
    }

    private fun notifyDefaultInterface() {
        val listener = defaultInterfaceListener ?: return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        if (network == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val linkProperties = connectivity.getLinkProperties(network)
        val name = linkProperties?.interfaceName ?: ""
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val capabilities = connectivity.getNetworkCapabilities(network)
        val expensive = capabilities != null &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        listener.updateDefaultInterface(name, index, expensive, false)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val javaInterfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrDefault(emptyList())
        val result = mutableListOf<BoxNetworkInterface>()
        for (network in connectivity.allNetworks) {
            val linkProperties = connectivity.getLinkProperties(network) ?: continue
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            val name = linkProperties.interfaceName ?: continue
            val javaInterface = javaInterfaces.find { it.name == name } ?: continue
            val boxInterface = BoxNetworkInterface()
            boxInterface.name = name
            boxInterface.index = javaInterface.index
            boxInterface.mtu = runCatching { javaInterface.mtu }.getOrDefault(0)
            boxInterface.dnsServer = StringIter(
                linkProperties.dnsServers.mapNotNull { it.hostAddress },
            )
            boxInterface.type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            boxInterface.addresses = StringIter(
                javaInterface.interfaceAddresses.map { address ->
                    val host = if (address.address is Inet6Address) {
                        Inet6Address.getByAddress(address.address.address).hostAddress
                    } else {
                        address.address.hostAddress
                    }
                    "$host/${address.networkPrefixLength}"
                },
            )
            var flags = 0
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (javaInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (javaInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (javaInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            boxInterface.flags = flags
            boxInterface.metered =
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result.add(boxInterface)
        }
        return NetworkInterfaceIter(result)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun systemCertificates(): StringIterator {
        val certificates = mutableListOf<String>()
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidCAStore")
            keyStore.load(null, null)
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val certificate = keyStore.getCertificate(aliases.nextElement()) ?: continue
                val encoded = Base64.encodeToString(certificate.encoded, Base64.DEFAULT).trim()
                certificates.add("-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----")
            }
        }
        return StringIter(certificates)
    }

    override fun clearDNSCache() {
    }

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun writeLog(message: String) {
        Log.d(TAG, message)
    }

    override fun sendNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val androidNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setSmallIcon(R.drawable.ic_launcher)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(notification.typeID + 1000, androidNotification) }
    }
}
