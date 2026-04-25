import Foundation
import NetworkExtension

/**
 OpenVPN bridge plugin.

 NOTE: Apple's NetworkExtension framework does not ship an OpenVPN parser.
 You must integrate a Network Extension target backed by an OpenVPN
 implementation such as `OpenVPNAdapter` (CocoaPods / SPM). The classes
 below wire the bridge surface; fill in the TODO sections with calls into
 your NETunnelProviderManager + extension.
 */
@objc(OpenVpnPlugin)
class OpenVpnPlugin: NSObject {

    fileprivate static var lastStatus: String = "disconnected"

    @objc(OpenVpnPluginIsSupported)
    class IsSupported: NSObject {
        @objc
        func execute(_ parameters: [String: Any]) -> [String: Any] {
            // iOS 9+ supports Personal VPN / NE tunnels on real devices.
            return [
                "success": true,
                "supported": true
            ]
        }
    }

    @objc(OpenVpnPluginRequestPermission)
    class RequestPermission: NSObject {
        @objc
        func execute(_ parameters: [String: Any]) -> [String: Any] {
            let semaphore = DispatchSemaphore(value: 0)
            var response: [String: Any] = [
                "success": false,
                "granted": false,
                "message": "Failed to prepare VPN configuration."
            ]

            NETunnelProviderManager.loadAllFromPreferences { managers, error in
                if let error = error {
                    response = [
                        "success": false,
                        "granted": false,
                        "message": error.localizedDescription
                    ]
                    semaphore.signal()
                    return
                }

                let manager = managers?.first ?? NETunnelProviderManager()
                manager.localizedDescription = "Projectmata VPN"
                manager.isEnabled = true

                manager.saveToPreferences { saveError in
                    if let saveError = saveError {
                        response = [
                            "success": false,
                            "granted": false,
                            "message": saveError.localizedDescription
                        ]
                    } else {
                        response = [
                            "success": true,
                            "granted": true
                        ]
                    }
                    semaphore.signal()
                }
            }

            semaphore.wait()
            return response
        }
    }

    @objc(OpenVpnPluginConnect)
    class Connect: NSObject {
        @objc
        func execute(_ parameters: [String: Any]) -> [String: Any] {
            let profile = (parameters["profile"] as? String) ?? ""
            let displayName = (parameters["displayName"] as? String) ?? "Projectmata VPN"

            if profile.isEmpty {
                return [
                    "success": false,
                    "message": "OpenVPN profile (.ovpn content) is required."
                ]
            }

            // TODO: build a NETunnelProviderProtocol whose providerBundleIdentifier
            // points at your OpenVPN NE target, attach `profile`, `username`,
            // `password` via providerConfiguration, save, then call
            // `connection.startVPNTunnel()`.
            OpenVpnPlugin.lastStatus = "connecting"

            return [
                "success": true,
                "status": OpenVpnPlugin.lastStatus,
                "displayName": displayName
            ]
        }
    }

    @objc(OpenVpnPluginDisconnect)
    class Disconnect: NSObject {
        @objc
        func execute(_ parameters: [String: Any]) -> [String: Any] {
            // TODO: call `connection.stopVPNTunnel()` on the active manager.
            OpenVpnPlugin.lastStatus = "disconnected"

            return [
                "success": true,
                "status": OpenVpnPlugin.lastStatus
            ]
        }
    }

    @objc(OpenVpnPluginGetStatus)
    class GetStatus: NSObject {
        @objc
        func execute(_ parameters: [String: Any]) -> [String: Any] {
            return [
                "success": true,
                "status": OpenVpnPlugin.lastStatus
            ]
        }
    }
}
