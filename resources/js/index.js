const baseUrl = '/_native/api/call';

async function bridgeCall(method, params = {}) {
    const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content || '';
    const url = `${baseUrl}?method=${encodeURIComponent(method)}`;

    const response = await fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
            'X-CSRF-TOKEN': csrfToken,
        },
        body: JSON.stringify({ method, params }),
    });

    const result = await response.json();

    if (result?.status === 'error') {
        const error = new Error(result.message || `Native bridge error: ${response.status}`);
        error.code = result.code || result.error?.code || 'OPENVPN_BRIDGE_ERROR';
        throw error;
    }

    if (!response.ok) {
        throw new Error(result?.message || `Native bridge error: ${response.status}`);
    }

    return result?.data ?? result;
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

export function disconnect() {
    if (pendingDisconnect) return pendingDisconnect;
    connectionGeneration += 1;
    const connecting = pendingConnection?.promise;
    const promise = (async () => {
        if (connecting) {
            try { await connecting; } catch { /* Stop after any in-flight launch completes. */ }
        }
        return stopNativeConnection();
    })();
    pendingDisconnect = promise.finally(() => { pendingDisconnect = null; });
    return pendingDisconnect;
}

export async function getStatus() {
    return bridgeCall('OpenVpn.GetStatus', {});
}

export async function getEvents({ sinceTs = 0, limit = 100 } = {}) {
    return bridgeCall('OpenVpn.GetEvents', { sinceTs, limit });
}

let pendingConnection = null;
let pendingDisconnect = null;
let connectionGeneration = 0;

function vpnError(code, message) {
    const error = new Error(message);
    error.code = code;
    return error;
}

async function stopNativeConnection() {
    const response = await bridgeCall('OpenVpn.Disconnect', {});
    if (response?.success === false) {
        throw vpnError('OPENVPN_DISCONNECT_ERROR', 'The native VPN stop request failed.');
    }
    const deadline = Date.now() + 10000;
    while (true) {
        const state = await getStatus();
        if (state?.disconnectError) {
            throw vpnError('OPENVPN_DISCONNECT_ERROR', 'The native VPN could not be stopped.');
        }
        const running = ['connected', 'connecting', 'starting'].includes(state?.status);
        if (!state?.disconnectPending && !state?.active && !running) return state;
        if (Date.now() >= deadline) {
            throw vpnError('OPENVPN_DISCONNECT_TIMEOUT', 'The VPN engine did not stop in time.');
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
    }
}

/**
 * One-call Android connection. Profile is .ovpn TEXT, not a URL or file path.
 * Optional credentials are necessary only when the VPN server requires them.
 * Resolves on the engine's connected state, not merely on launch acknowledgement.
 */
export function connectProfile(profile, options = {}) {
    if (pendingDisconnect) {
        return Promise.reject(vpnError('OPENVPN_BUSY', 'VPN disconnection is still in progress.'));
    }
    if (pendingConnection) {
        if (pendingConnection.profile === profile
            && pendingConnection.username === options.username
            && pendingConnection.password === options.password) {
            return pendingConnection.promise;
        }
        return Promise.reject(vpnError('OPENVPN_BUSY', 'Another VPN connection is being prepared.'));
    }
    const generation = ++connectionGeneration;
    const promise = establishConnection(profile, options, generation).finally(() => {
        if (pendingConnection?.promise === promise) pendingConnection = null;
    });
    pendingConnection = { profile, username: options.username, password: options.password, promise };
    return promise;
}

async function establishConnection(profile, options, generation) {
    const checkCancelled = () => {
        if (options.signal?.aborted || generation !== connectionGeneration) {
            throw vpnError('OPENVPN_CANCELLED', 'VPN connection cancelled.');
        }
    };
    const pollInterval = Math.max(1, options.pollIntervalMs ?? 750);
    const sleep = () => new Promise((resolve) => setTimeout(resolve, pollInterval));
    let launched = false;
    try {
        if (typeof profile !== 'string' || !profile.trim()) {
            throw vpnError('OPENVPN_BAD_PROFILE', 'OpenVPN profile text is required.');
        }
        checkCancelled();
        const support = await isSupported();
        if (!support?.supported) {
            throw vpnError('OPENVPN_UNSUPPORTED', 'This native build does not support OpenVPN.');
        }
        checkCancelled();
        await requestPermission();
        const permissionDeadline = Date.now() + (options.permissionTimeoutMs ?? 60000);
        let launch;
        while (true) {
            checkCancelled();
            try {
                launch = await connect({ ...options, profile });
                if (launch?.success === false) {
                    throw vpnError('OPENVPN_CONNECT_ERROR', 'The native engine could not start the VPN.');
                }
                launched = true;
                break;
            } catch (error) {
                const waitingForConsent = error.code === 'OPENVPN_PERMISSION_REQUIRED'
                    || String(error.message).includes('VPN permission has not been granted');
                if (!waitingForConsent) throw error;
                if (Date.now() >= permissionDeadline) {
                    throw vpnError('OPENVPN_PERMISSION_TIMEOUT', 'Android VPN permission was not granted in time.');
                }
                await sleep();
            }
        }
        const deadline = Date.now() + (options.connectionTimeoutMs ?? 60000);
        while (true) {
            checkCancelled();
            const state = await getStatus();
            checkCancelled();
            if (state?.status === 'connected') return { ...launch, ...state, success: true };
            if (state?.status === 'auth_failed') {
                throw vpnError('OPENVPN_AUTH_FAILED', 'The VPN server rejected the supplied identity.');
            }
            if (state?.status === 'no_network') {
                throw vpnError('OPENVPN_NO_NETWORK', 'No network is available for the VPN connection.');
            }
            if (Date.now() >= deadline) {
                throw vpnError('OPENVPN_CONNECTION_TIMEOUT', 'The VPN engine did not connect in time.');
            }
            await sleep();
        }
    } catch (error) {
        if (launched) {
            try { await stopNativeConnection(); } catch { /* Preserve the original error. */ }
        }
        throw error;
    }
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
    ConnectProfile(profile, options = {}) {
        return connectProfile(profile, options);
    },
    Disconnect() {
        return disconnect();
    },
    GetStatus() {
        return getStatus();
    },
    GetEvents(options = {}) {
        return getEvents(options);
    },
};

if (typeof window !== 'undefined') {
    window.NativePHP = window.NativePHP || {};
    window.NativePHP.OpenVpn = OpenVpn;
}

export default OpenVpn;
