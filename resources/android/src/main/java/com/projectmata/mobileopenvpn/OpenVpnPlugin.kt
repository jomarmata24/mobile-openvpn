package com.projectmata.mobileopenvpn

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.IServiceStatus
import de.blinkt.openvpn.core.IStatusCallbacks
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.OpenVPNStatusService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenVPN bridge — backed by ics-openvpn (de.blinkt.openvpn.*).
 *
 * Captures every state transition + log line from ics-openvpn into a
 * ring buffer the WebView can read via OpenVpn.GetEvents — surfaces
 * AUTH_FAILED / NETWORK_UNREACHABLE / TLS errors that would otherwise
 * vanish into encrypted logcat.
 */
class OpenVpnPlugin {

    companion object {
        private const val MAX_EVENTS = 200
        private val events = ConcurrentLinkedDeque<Map<String, Any?>>()
        private val lastState = AtomicReference<String>("disconnected")
        private val lastDetail = AtomicReference<String>("")
        @Volatile private var listenersInstalled = false
        @Volatile private var appContext: Context? = null
        @Volatile private var statusService: IServiceStatus? = null
        @Volatile private var statusServiceBound = false
        @Volatile private var disconnectPending = false
        @Volatile private var disconnectError = ""

        private val statusCallback = object : IStatusCallbacks.Stub() {
            override fun newLogItem(item: LogItem?) {
                push(mapOf(
                    "kind" to "log",
                    "level" to (item?.logLevel?.name ?: ""),
                    "message" to (item?.getString(appContext) ?: item?.toString() ?: ""),
                    "ts" to System.currentTimeMillis()
                ))
            }

            override fun updateStateString(
                state: String?,
                msg: String?,
                resid: Int,
                level: ConnectionStatus?,
                intent: Intent?
            ) {
                val mapped = stateToStatus(level)
                lastState.set(mapped)
                lastDetail.set("${state ?: ""}: ${msg ?: ""}")
                push(mapOf(
                    "kind" to "state",
                    "state" to (state ?: ""),
                    "level" to (level?.name ?: ""),
                    "status" to mapped,
                    "message" to (msg ?: ""),
                    "ts" to System.currentTimeMillis()
                ))
            }

            override fun updateByteCount(inBytes: Long, outBytes: Long) {
                push(mapOf(
                    "kind" to "traffic",
                    "inBytes" to inBytes,
                    "outBytes" to outBytes,
                    "ts" to System.currentTimeMillis()
                ))
            }

            override fun connectedVPN(uuid: String?) {
                push(mapOf(
                    "kind" to "connectedProfile",
                    "uuid" to (uuid ?: ""),
                    "ts" to System.currentTimeMillis()
                ))
            }

            override fun notifyProfileVersionChanged(uuid: String?, profileVersion: Int) {
                push(mapOf(
                    "kind" to "profileVersion",
                    "uuid" to (uuid ?: ""),
                    "version" to profileVersion,
                    "ts" to System.currentTimeMillis()
                ))
            }
        }

        private val statusConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    statusService = IServiceStatus.Stub.asInterface(binder)
                    val historicalLogs = statusService?.registerStatusCallback(statusCallback)
                    historicalLogs?.close()
                    push(mapOf(
                        "kind" to "diagnostic",
                        "message" to "Bound to OpenVPN status service",
                        "ts" to System.currentTimeMillis()
                    ))
                } catch (e: Exception) {
                    push(mapOf(
                        "kind" to "diagnostic",
                        "level" to "ERROR",
                        "message" to "Failed to register OpenVPN status callback: ${e.message ?: e.javaClass.simpleName}",
                        "ts" to System.currentTimeMillis()
                    ))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                statusService = null
                statusServiceBound = false
                push(mapOf(
                    "kind" to "diagnostic",
                    "message" to "OpenVPN status service disconnected",
                    "ts" to System.currentTimeMillis()
                ))
            }
        }

        fun installListenersOnce(context: Context) {
            appContext = context.applicationContext
            bindStatusService(context)

            if (listenersInstalled) return
            synchronized(this) {
                if (listenersInstalled) return
                VpnStatus.addStateListener(object : VpnStatus.StateListener {
                    override fun updateState(
                        state: String?,
                        logmessage: String?,
                        localizedResId: Int,
                        level: ConnectionStatus?,
                        intent: Intent?
                    ) {
                        val mapped = stateToStatus(level)
                        lastState.set(mapped)
                        lastDetail.set("${state ?: ""}: ${logmessage ?: ""}")
                        push(mapOf(
                            "kind" to "state",
                            "state" to (state ?: ""),
                            "level" to (level?.name ?: ""),
                            "status" to mapped,
                            "message" to (logmessage ?: ""),
                            "ts" to System.currentTimeMillis()
                        ))
                    }

                    override fun setConnectedVPN(uuid: String?) {
                        push(mapOf(
                            "kind" to "connectedProfile",
                            "uuid" to (uuid ?: ""),
                            "ts" to System.currentTimeMillis()
                        ))
                    }
                })
                VpnStatus.addLogListener(object : VpnStatus.LogListener {
                    override fun newLog(entry: LogItem?) {
                        push(mapOf(
                            "kind" to "log",
                            "level" to (entry?.logLevel?.name ?: ""),
                            "message" to (entry?.getString(appContext) ?: entry?.toString() ?: ""),
                            "ts" to System.currentTimeMillis()
                        ))
                    }
                })
                listenersInstalled = true
            }
        }

        private fun bindStatusService(context: Context) {
            if (statusServiceBound) return

            try {
                val app = context.applicationContext
                val intent = Intent(app, OpenVPNStatusService::class.java)
                statusServiceBound = app.bindService(intent, statusConnection, Context.BIND_AUTO_CREATE)
                push(mapOf(
                    "kind" to "diagnostic",
                    "message" to if (statusServiceBound) "Binding OpenVPN status service" else "OpenVPN status service bind returned false",
                    "ts" to System.currentTimeMillis()
                ))
            } catch (e: Exception) {
                statusServiceBound = false
                push(mapOf(
                    "kind" to "diagnostic",
                    "level" to "ERROR",
                    "message" to "OpenVPN status bind failed: ${e.message ?: e.javaClass.simpleName}",
                    "ts" to System.currentTimeMillis()
                ))
            }
        }

        private fun push(e: Map<String, Any?>) {
            events.add(e)
            while (events.size > MAX_EVENTS) events.pollFirst()
        }

        private fun isVpnTransportActive(context: Context): Boolean {
            return try {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                manager.allNetworks.any { network ->
                    manager.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            } catch (_: Exception) {
                false
            }
        }

        fun stateToStatus(state: ConnectionStatus?): String = when (state) {
            ConnectionStatus.LEVEL_CONNECTED            -> "connected"
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT,
            ConnectionStatus.LEVEL_START                -> "connecting"
            ConnectionStatus.LEVEL_AUTH_FAILED          -> "auth_failed"
            ConnectionStatus.LEVEL_NONETWORK            -> "no_network"
            ConnectionStatus.LEVEL_NOTCONNECTED         -> "disconnected"
            else                                        -> "disconnected"
        }
    }

    class IsSupported(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                installListenersOnce(activity)
                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "supported" to true
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    "OPENVPN_SUPPORT_ERROR",
                    e.message ?: "Failed to check VPN support."
                )
            }
        }
    }

    class RequestPermission(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                installListenersOnce(activity)
                val intent: Intent? = VpnService.prepare(activity)

                if (intent == null) {
                    return BridgeResponse.success(
                        mapOf<String, Any>(
                            "success" to true,
                            "granted" to true
                        )
                    )
                }

                activity.runOnUiThread {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(intent, 7701)
                }

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "granted" to false,
                        "message" to "VPN permission prompt shown. Await user consent."
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    "OPENVPN_PERMISSION_ERROR",
                    e.message ?: "Failed to request VPN permission."
                )
            }
        }
    }

    class Connect(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                installListenersOnce(activity)

                val profileText = parameters["profile"] as? String ?: ""
                val username    = parameters["username"] as? String
                val password    = parameters["password"] as? String
                val displayName = (parameters["displayName"] as? String)
                    ?.takeIf { it.isNotBlank() } ?: "Projectmata VPN"

                if (profileText.isBlank()) {
                    return BridgeResponse.error(
                        "OPENVPN_BAD_PROFILE",
                        "OpenVPN profile (.ovpn content) is required."
                    )
                }

                if (VpnService.prepare(activity) != null) {
                    return BridgeResponse.error(
                        "OPENVPN_PERMISSION_REQUIRED",
                        "VPN permission has not been granted. Call RequestPermission first."
                    )
                }

                val parser = ConfigParser()
                parser.parseConfig(StringReader(profileText))
                val profile: VpnProfile = parser.convertProfile()
                profile.mName = displayName
                if (!profile.mPKCS12Filename.isNullOrBlank()) {
                    return BridgeResponse.error(
                        "OPENVPN_UNSUPPORTED_PROFILE",
                        "PKCS12 identity requires a keystore import. Use an inline PEM certificate/key profile for automatic connection."
                    )
                }

                // Preserve combined certificate/password authentication when a
                // real client certificate was supplied; a CA alone is not one.
                if (!username.isNullOrEmpty()) {
                    val hasClientCertificate = !profile.mClientCertFilename.isNullOrBlank()
                        && !profile.mClientKeyFilename.isNullOrBlank()
                    if (!hasClientCertificate &&
                        profile.mAuthenticationType == VpnProfile.TYPE_USERPASS_CERTIFICATES) {
                        profile.mAuthenticationType = VpnProfile.TYPE_USERPASS
                    }
                    profile.mUsername = username
                    profile.mPassword = password ?: ""
                }
                val passwordAuthentication = profile.mAuthenticationType in setOf(
                    VpnProfile.TYPE_USERPASS,
                    VpnProfile.TYPE_USERPASS_CERTIFICATES,
                    VpnProfile.TYPE_USERPASS_PKCS12,
                    VpnProfile.TYPE_USERPASS_KEYSTORE
                )
                if (passwordAuthentication && (profile.mUsername.isNullOrBlank() || profile.mPassword.isNullOrEmpty())) {
                    return BridgeResponse.error(
                        "OPENVPN_CREDENTIALS_REQUIRED",
                        "This VPN profile requires username/password credentials. Supply them with the profile."
                    )
                }

                val appContext = activity.applicationContext
                val profileManager = ProfileManager.getInstance(appContext)
                profileManager.getProfileByName(displayName)?.let { existing ->
                    profile.setUUID(existing.getUUID())
                    profile.mVersion = existing.mVersion + 1
                }
                profileManager.addProfile(profile)
                ProfileManager.saveProfile(appContext, profile)
                profileManager.saveProfileList(appContext)
                statusService?.notifyProfileVersionChanged(profile.getUUIDString(), profile.mVersion)
                push(mapOf(
                    "kind" to "diagnostic",
                    "message" to "OpenVPN profile saved and launch requested",
                    "profileName" to profile.mName,
                    "uuid" to profile.getUUIDString(),
                    "version" to profile.mVersion,
                    "authType" to profile.mAuthenticationType,
                    "hasUsername" to !username.isNullOrEmpty(),
                    "ts" to System.currentTimeMillis()
                ))
                lastState.set("connecting")
                lastDetail.set("")
                disconnectError = ""
                VPNLaunchHelper.startOpenVpn(profile, appContext, displayName, true)

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "status" to "starting",
                        "displayName" to displayName,
                        "authType" to profile.mAuthenticationType,
                        "hasUsername" to !username.isNullOrEmpty()
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    "OPENVPN_CONNECT_ERROR",
                    e.message ?: "Failed to start OpenVPN connection."
                )
            }
        }
    }

    class Disconnect(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                if (disconnectPending) {
                    return BridgeResponse.success(mapOf("success" to true, "status" to "disconnecting", "disconnectPending" to true))
                }
                disconnectError = ""
                disconnectPending = true
                val intent = Intent(activity, OpenVPNService::class.java).apply {
                    action = OpenVPNService.START_SERVICE
                }

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        try {
                            val service = IOpenVPNServiceInternal.Stub.asInterface(binder)
                            if (service == null) {
                                disconnectError = "VPN service binder was unavailable."
                            } else if (!service.stopVPN(false)) {
                                // Handles a service still starting or already without management.
                                activity.stopService(Intent(activity, OpenVPNService::class.java))
                            }
                        } catch (error: Exception) {
                            disconnectError = "VPN service stop failed."
                        } finally {
                            disconnectPending = false
                        }
                        try { activity.unbindService(this) } catch (_: Exception) {}
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                }

                if (!activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    disconnectPending = false
                    disconnectError = "VPN service could not be bound."
                    return BridgeResponse.error("OPENVPN_DISCONNECT_ERROR", disconnectError)
                }
                ProfileManager.setConntectedVpnProfileDisconnected(activity)

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "status" to "disconnecting",
                        "disconnectPending" to true
                    )
                )
            } catch (e: Exception) {
                disconnectPending = false
                BridgeResponse.error(
                    "OPENVPN_DISCONNECT_ERROR",
                    e.message ?: "Failed to disconnect OpenVPN."
                )
            }
        }
    }

    class GetStatus(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            installListenersOnce(activity)
            val transportActive = isVpnTransportActive(activity)
            return BridgeResponse.success(
                mapOf<String, Any>(
                    "success" to true,
                    "status" to lastState.get(),
                    "active" to VpnStatus.isVPNActive(),
                    "transportActive" to transportActive,
                    "statusServiceBound" to statusServiceBound,
                    "disconnectPending" to disconnectPending,
                    "disconnectError" to disconnectError,
                    "detail" to lastDetail.get()
                )
            )
        }
    }

    /**
     * Returns recent state transitions and log lines from ics-openvpn.
     * params:
     *   sinceTs: Long (optional) — only events newer than this ms timestamp
     *   limit:   Int  (optional, default 100, max 200)
     */
    class GetEvents(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            installListenersOnce(activity)
            val sinceTs = (parameters["sinceTs"] as? Number)?.toLong() ?: 0L
            val limit   = (parameters["limit"]   as? Number)?.toInt()?.coerceIn(1, MAX_EVENTS) ?: 100
            val transportActive = isVpnTransportActive(activity)

            val snapshot = events.toList()
                .filter { ((it["ts"] as? Number)?.toLong() ?: 0L) > sinceTs }
                .takeLast(limit)

            return BridgeResponse.success(
                mapOf<String, Any>(
                    "success" to true,
                    "status" to lastState.get(),
                    "active" to VpnStatus.isVPNActive(),
                    "transportActive" to transportActive,
                    "statusServiceBound" to statusServiceBound,
                    "disconnectPending" to disconnectPending,
                    "disconnectError" to disconnectError,
                    "count" to snapshot.size,
                    "events" to snapshot
                )
            )
        }
    }
}
