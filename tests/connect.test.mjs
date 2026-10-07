import test from 'node:test';
import assert from 'node:assert/strict';
import { connectProfile, disconnect } from '../resources/js/index.js';

globalThis.document = { querySelector: () => null };

function mockBridge(handler) {
    const calls = [];
    globalThis.fetch = async (_url, request) => {
        const { method, params } = JSON.parse(request.body);
        calls.push({ method, params });
        return { ok: true, status: 200, json: async () => ({ data: await handler(method, params) }) };
    };
    return calls;
}

test('profile-only call requests permission, launches once and waits for connected', async () => {
    let checks = 0;
    const calls = mockBridge((method) => ({
        'OpenVpn.IsSupported': { supported: true },
        'OpenVpn.RequestPermission': { granted: true },
        'OpenVpn.Connect': { status: 'starting' },
        'OpenVpn.GetStatus': { status: ++checks > 4 ? 'connected' : 'connecting' },
    })[method]);
    const result = await connectProfile('client\ndev tun', { pollIntervalMs: 1 });
    assert.equal(result.status, 'connected');
    assert.deepEqual(calls.slice(0, 3).map((call) => call.method), [
        'OpenVpn.IsSupported', 'OpenVpn.RequestPermission', 'OpenVpn.Connect',
    ]);
    assert.equal(calls[2].params.profile, 'client\ndev tun');
    assert.equal(calls.filter((call) => call.method === 'OpenVpn.Connect').length, 1);
});

test('auth failure is reported without sensitive details', async () => {
    mockBridge((method) => ({
        'OpenVpn.IsSupported': { supported: true },
        'OpenVpn.RequestPermission': { granted: true },
        'OpenVpn.Connect': { status: 'starting' },
        'OpenVpn.GetStatus': { status: 'auth_failed', detail: 'secret diagnostic' },
        'OpenVpn.Disconnect': { status: 'disconnected' },
    })[method]);
    await assert.rejects(connectProfile('secret profile', { password: 'secret' }),
        (error) => error.code === 'OPENVPN_AUTH_FAILED' && !error.message.includes('secret'));
});

test('unsupported runtime never prompts or launches', async () => {
    const calls = mockBridge(() => ({ supported: false }));
    await assert.rejects(connectProfile('client'), { code: 'OPENVPN_UNSUPPORTED' });
    assert.equal(calls.length, 1);
});

test('blank profile fails before bridge request', async () => {
    const calls = mockBridge(() => { throw new Error('Must not call'); });
    await assert.rejects(connectProfile('  '), { code: 'OPENVPN_BAD_PROFILE' });
    assert.equal(calls.length, 0);
});

test('connection timeout stops the launched engine', async () => {
    let stopped = false;
    const calls = mockBridge((method) => {
        if (method === 'OpenVpn.Disconnect') stopped = true;
        return ({
        'OpenVpn.IsSupported': { supported: true },
        'OpenVpn.RequestPermission': { granted: true },
        'OpenVpn.Connect': { status: 'starting' },
        'OpenVpn.GetStatus': { status: stopped ? 'disconnected' : 'connecting' },
        'OpenVpn.Disconnect': { status: 'disconnected' },
        })[method];
    });
    await assert.rejects(connectProfile('client', { connectionTimeoutMs: 5, pollIntervalMs: 1 }),
        { code: 'OPENVPN_CONNECTION_TIMEOUT' });
    assert.ok(calls.some((call) => call.method === 'OpenVpn.Disconnect'));
});

test('disconnect during a delayed native launch still stops the launched engine', async () => {
    let active = false;
    let release;
    let started;
    const launching = new Promise((resolve) => { started = resolve; });
    const delayed = new Promise((resolve) => { release = resolve; });
    const calls = mockBridge(async (method) => {
        if (method === 'OpenVpn.IsSupported') return { supported: true };
        if (method === 'OpenVpn.RequestPermission') return { granted: true };
        if (method === 'OpenVpn.Connect') { started(); await delayed; active = true; return { status: 'starting' }; }
        if (method === 'OpenVpn.Disconnect') { active = false; return { status: 'disconnected' }; }
        if (method === 'OpenVpn.GetStatus') return { status: active ? 'connected' : 'disconnected' };
        throw new Error('Unexpected '+method);
    });
    const connecting = connectProfile('client');
    const rejected = assert.rejects(connecting, { code: 'OPENVPN_CANCELLED' });
    await launching;
    const stopping = disconnect();
    release();
    await rejected;
    await stopping;
    assert.ok(calls.some((call) => call.method === 'OpenVpn.Disconnect'));
    assert.equal(active, false);
});

test('pending consent is retried using the real NativePHP HTTP error envelope', async () => {
    let launches = 0;
    globalThis.fetch = async (_url, request) => {
        const { method } = JSON.parse(request.body);
        if (method === 'OpenVpn.Connect' && ++launches === 1) {
            return { ok: false, status: 400, json: async () => ({
                status: 'error', code: 'OPENVPN_PERMISSION_REQUIRED', message: 'Consent pending',
            }) };
        }
        const data = ({
            'OpenVpn.IsSupported': { supported: true },
            'OpenVpn.RequestPermission': { granted: false },
            'OpenVpn.Connect': { status: 'starting' },
            'OpenVpn.GetStatus': { status: 'connected' },
        })[method];
        return { ok: true, status: 200, json: async () => ({ status: 'success', data }) };
    };
    assert.equal((await connectProfile('client', { pollIntervalMs: 1 })).status, 'connected');
    assert.equal(launches, 2);
});

test('abort while status request resolves cannot return connected', async () => {
    const abort = new AbortController();
    let stopped = false;
    const calls = mockBridge((method) => {
        if (method === 'OpenVpn.IsSupported') return { supported: true };
        if (method === 'OpenVpn.RequestPermission') return { granted: true };
        if (method === 'OpenVpn.Connect') return { status: 'starting' };
        if (method === 'OpenVpn.GetStatus') { abort.abort(); return { status: stopped ? 'disconnected' : 'connected' }; }
        if (method === 'OpenVpn.Disconnect') { stopped = true; return { status: 'disconnected' }; }
    });
    await assert.rejects(connectProfile('client', { signal: abort.signal }), { code: 'OPENVPN_CANCELLED' });
    assert.ok(calls.some((call) => call.method === 'OpenVpn.Disconnect'));
});

test('simultaneous identical connect requests share one launch', async () => {
    const calls = mockBridge((method) => ({
        'OpenVpn.IsSupported': { supported: true },
        'OpenVpn.RequestPermission': { granted: true },
        'OpenVpn.Connect': { status: 'starting' },
        'OpenVpn.GetStatus': { status: 'connected' },
    })[method]);
    const first = connectProfile('client');
    const second = connectProfile('client');
    assert.equal(first, second);
    await first;
    assert.equal(calls.filter((call) => call.method === 'OpenVpn.Connect').length, 1);
});

test('disconnect waits for deferred native stop before allowing reconnect', async () => {
    let checks = 0;
    const calls = mockBridge((method) => {
        if (method === 'OpenVpn.Disconnect') return { status: 'disconnecting', disconnectPending: true };
        if (method === 'OpenVpn.GetStatus') return ++checks < 2
            ? { status: 'connected', disconnectPending: true }
            : { status: 'disconnected', disconnectPending: false };
    });
    const stopping = disconnect();
    await assert.rejects(connectProfile('client'), { code: 'OPENVPN_BUSY' });
    const result = await stopping;
    assert.equal(result.status, 'disconnected');
    assert.equal(calls.filter((call) => call.method === 'OpenVpn.GetStatus').length, 2);
});
