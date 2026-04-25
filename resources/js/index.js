const baseUrl = '/_native/api/call';

async function bridgeCall(method, params = {}) {
    const response = await fetch(baseUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
        },
        body: JSON.stringify({ method, params }),
    });

    const data = await response.json();

    if (!response.ok) {
        throw new Error(data?.message || `Native bridge error: ${response.status}`);
    }

    return data;
}

export async function isSupported() {
    return bridgeCall('OpenVpn.IsSupported', {});
}

export async function requestPermission() {
    return bridgeCall('OpenVpn.RequestPermission', {});
}

export async function connect(options = {}) {
    return bridgeCall('OpenVpn.Connect', {
        profile: options.profile ?? '',
        username: options.username ?? null,
        password: options.password ?? null,
        displayName: options.displayName ?? null,
    });
}

export async function disconnect() {
    return bridgeCall('OpenVpn.Disconnect', {});
}

export async function getStatus() {
    return bridgeCall('OpenVpn.GetStatus', {});
}

const OpenVpn = {
    IsSupported() {
        return isSupported();
    },
    RequestPermission() {
        return requestPermission();
    },
    Connect(options = {}) {
        return connect(options);
    },
    Disconnect() {
        return disconnect();
    },
    GetStatus() {
        return getStatus();
    },
};

if (typeof window !== 'undefined') {
    window.NativePHP = window.NativePHP || {};
    window.NativePHP.OpenVpn = OpenVpn;
}

export default OpenVpn;
