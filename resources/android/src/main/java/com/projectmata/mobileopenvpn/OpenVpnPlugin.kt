package com.projectmata.mobileopenvpn

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.bridge.BridgeError
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

/**
 * OpenVPN bridge — backed by ics-openvpn (de.blinkt.openvpn.*).
 *
 * Requires the ics-openvpn `:main` module to be on the classpath. See
 * the host app's settings.gradle.kts and app/build.gradle.kts for the
 * module wiring.
 */
class OpenVpnPlugin {

    companion object {
        private fun makeError(code: String, message: String): BridgeError {
            val ctor = BridgeError::class.java.getDeclaredConstructor(
                String::class.java,
                String::class.java
            )
            ctor.isAccessible = true
            return ctor.newInstance(code, message)
        }

        private fun stateToStatus(state: ConnectionStatus?): String = when (state) {
            ConnectionStatus.LEVEL_CONNECTED            -> "connected"
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT,
            ConnectionStatus.LEVEL_START                -> "connecting"
            ConnectionStatus.LEVEL_AUTH_FAILED          -> "auth_failed"
            else                                        -> "disconnected"
        }
    }

    class IsSupported(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                // Android always supports VpnService — the prepare() return value
                // tells us whether permission has been granted yet, not whether
                // VPN is supported on this device.
                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "supported" to true
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    makeError("OPENVPN_SUPPORT_ERROR", e.message ?: "Failed to check VPN support.")
                )
            }
        }
    }

    class RequestPermission(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
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
                    activity.startActivity(intent)
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
                    makeError("OPENVPN_PERMISSION_ERROR", e.message ?: "Failed to request VPN permission.")
                )
            }
        }
    }

    class Connect(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val profileText = parameters["profile"]?.toString() ?: ""
                val username    = parameters["username"]?.toString()
                val password    = parameters["password"]?.toString()
                val displayName = parameters["displayName"]?.toString() ?: "Projectmata VPN"

                if (profileText.isBlank()) {
                    return BridgeResponse.error(
                        makeError("OPENVPN_BAD_PROFILE", "OpenVPN profile (.ovpn content) is required.")
                    )
                }

                // Permission must already be granted (call RequestPermission first).
                if (VpnService.prepare(activity) != null) {
                    return BridgeResponse.error(
                        makeError(
                            "OPENVPN_PERMISSION_REQUIRED",
                            "VPN permission has not been granted. Call RequestPermission first."
                        )
                    )
                }

                // Parse the .ovpn into an ics-openvpn VpnProfile.
                val parser = ConfigParser()
                parser.parseConfig(StringReader(profileText))
                val profile: VpnProfile = parser.convertProfile()
                profile.mName = displayName

                if (!username.isNullOrEmpty()) {
                    profile.mUsername = username
                    profile.mPassword = password ?: ""
                }

                // Persist + start.
                ProfileManager.getInstance(activity).addProfile(profile)
                ProfileManager.getInstance(activity).saveProfile(activity, profile)
                VPNLaunchHelper.startOpenVpn(profile, activity)

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "status" to stateToStatus(VpnStatus.getLastLevel()),
                        "displayName" to displayName
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    makeError("OPENVPN_CONNECT_ERROR", e.message ?: "Failed to start OpenVPN connection.")
                )
            }
        }
    }

    class Disconnect(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val intent = Intent(activity, OpenVPNService::class.java).apply {
                    action = OpenVPNService.START_SERVICE
                }

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        try {
                            val service = IOpenVPNServiceInternal.Stub.asInterface(binder)
                            service?.stopVPN(false)
                        } catch (_: Exception) {
                            // best-effort — fall through to ProfileManager
                        }
                        try { activity.unbindService(this) } catch (_: Exception) {}
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                }

                activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                ProfileManager.setConntectedVpnProfileDisconnected(activity)

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "status" to "disconnected"
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    makeError("OPENVPN_DISCONNECT_ERROR", e.message ?: "Failed to disconnect OpenVPN.")
                )
            }
        }
    }

    class GetStatus(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return BridgeResponse.success(
                mapOf<String, Any>(
                    "success" to true,
                    "status" to stateToStatus(VpnStatus.getLastLevel())
                )
            )
        }
    }
}
