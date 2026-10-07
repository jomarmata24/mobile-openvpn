import Foundation

// Android-only runtime distribution. Report unsupported until an actual iOS
// Packet Tunnel Provider is included; never return a simulated connection.
@objc(OpenVpnPlugin)
class OpenVpnPlugin: NSObject {
    fileprivate static func unsupported() -> [String: Any] {
        return ["success": false, "supported": false, "status": "unsupported",
                "message": "This package does not yet include an iOS OpenVPN tunnel provider."]
    }

    @objc(OpenVpnPluginIsSupported)
    class IsSupported: NSObject {
        @objc func execute(_ parameters: [String: Any]) -> [String: Any] {
            return OpenVpnPlugin.unsupported()
        }
    }

    @objc(OpenVpnPluginRequestPermission)
    class RequestPermission: NSObject {
        @objc func execute(_ parameters: [String: Any]) -> [String: Any] {
            var result = OpenVpnPlugin.unsupported()
            result["granted"] = false
            return result
        }
    }

    @objc(OpenVpnPluginConnect)
    class Connect: NSObject {
        @objc func execute(_ parameters: [String: Any]) -> [String: Any] {
            return OpenVpnPlugin.unsupported()
        }
    }

    @objc(OpenVpnPluginDisconnect)
    class Disconnect: NSObject {
        @objc func execute(_ parameters: [String: Any]) -> [String: Any] {
            return OpenVpnPlugin.unsupported()
        }
    }

    @objc(OpenVpnPluginGetStatus)
    class GetStatus: NSObject {
        @objc func execute(_ parameters: [String: Any]) -> [String: Any] {
            return OpenVpnPlugin.unsupported()
        }
    }

    @objc(OpenVpnPluginGetEvents)
    class GetEvents: NSObject {
        @objc func execute(_ parameters: [String: Any]) -> [String: Any] {
            var result = OpenVpnPlugin.unsupported()
            result["events"] = []
            result["count"] = 0
            return result
        }
    }
}
