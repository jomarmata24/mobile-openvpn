package com.projectmata.mobileopenvpn

import android.content.Intent
import android.net.VpnService
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.bridge.BridgeError

/**
 * OpenVPN bridge plugin.
 *
 * NOTE: This class delegates the actual tunnel work to an OpenVPN runtime
 * (e.g. ics-openvpn or OpenVPN 3). Integrate the library of your
 * choice in the TODO sections below and wire the profile/credentials through.
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

        @Volatile
        private var lastStatus: String = "disconnected"
    }

    class IsSupported(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val supported = VpnService.prepare(activity) != null || true
                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "supported" to supported
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
                val profile = parameters["profile"]?.toString() ?: ""
                val username = parameters["username"]?.toString()
                val password = parameters["password"]?.toString()
                val displayName = parameters["displayName"]?.toString() ?: "Projectmata VPN"

                if (profile.isBlank()) {
                    return BridgeResponse.error(
                        makeError("OPENVPN_BAD_PROFILE", "OpenVPN profile (.ovpn content) is required.")
                    )
                }

                // TODO: hand `profile`, `username`, `password` to your OpenVPN
                // runtime (e.g. OpenVPNService from ics-openvpn) and start the tunnel.
                lastStatus = "connecting"

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "status" to lastStatus,
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
                // TODO: stop the OpenVPN service / tunnel.
                lastStatus = "disconnected"

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "status" to lastStatus
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
                    "status" to lastStatus
                )
            )
        }
    }
}
