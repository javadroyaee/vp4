package ir.avang.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.io.File

class AvangVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var tun2SocksProcess: Process? = null

    private val callbackHandler = object : CoreCallbackHandler {
        override fun Startup(): Int = 0
        override fun Shutdown(): Int = 0
        override fun OnEmitStatus(status: Int, msg: String?): Int = 0
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        coreController = CoreController(callbackHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }
        val xrayJson = intent?.getStringExtra("XRAY_JSON")
        val serverName = intent?.getStringExtra("SERVER_NAME") ?: "Avang VPN"
        if (!xrayJson.isNullOrEmpty()) {
            startForegroundNotification(serverName)
            startXrayTunnel(xrayJson)
        }
        return START_STICKY
    }

    private fun startXrayTunnel(xrayJson: String) {
        stopTunnelOnly()
        try {
            coreController?.startLoop(xrayJson)

            val builder = Builder()
                .setSession("Avang VPN")
                .setMtu(1500)
                .addAddress("10.0.0.2", 30)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            val fd = vpnInterface?.fd ?: run {
                coreController?.stopLoop()
                return
            }

            val tunBin = File(applicationInfo.nativeLibraryDir, "libtun2socks.so")
            if (tunBin.exists()) {
                val cmd = listOf(
                    tunBin.absolutePath,
                    "-device", "fd://$fd",
                    "-proxy", "socks5://127.0.0.1:10808",
                    "-mtu", "1500"
                )
                tun2SocksProcess = ProcessBuilder(cmd).redirectErrorStream(true).start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopTunnelOnly() {
        try { tun2SocksProcess?.destroy(); tun2SocksProcess = null } catch (_: Exception) {}
        try { coreController?.stopLoop() } catch (_: Exception) {}
        try { vpnInterface?.close(); vpnInterface = null } catch (_: Exception) {}
    }

    private fun stopVpn() {
        stopTunnelOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("avang_vpn_ch", "Avang VPN Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(serverName: String) {
        val notif = NotificationCompat.Builder(this, "avang_vpn_ch")
            .setContentTitle("Avang VPN • Connected")
            .setContentText("متصل به: $serverName")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
