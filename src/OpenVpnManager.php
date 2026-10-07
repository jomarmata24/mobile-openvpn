<?php

namespace Projectmata\MobileOpenVpn;

class OpenVpnManager
{
    protected function callNative(string $method, array $params = []): mixed
    {
        if (! function_exists('nativephp_call')) {
            return [
                'success' => false,
                'message' => 'NativePHP bridge helper not available.',
            ];
        }

        $json = json_encode($params);

        if ($json === false) {
            return [
                'success' => false,
                'message' => 'Failed to encode NativePHP bridge parameters.',
            ];
        }

        return nativephp_call($method, $json);
    }

    public function isSupported(): mixed
    {
        return $this->callNative('OpenVpn.IsSupported');
    }

    public function requestPermission(): mixed
    {
        return $this->callNative('OpenVpn.RequestPermission');
    }

    public function connect(
        string $profile,
        ?string $username = null,
        ?string $password = null,
        ?string $displayName = null
    ): mixed {
        return $this->callNative('OpenVpn.Connect', [
            'profile' => $profile,
            'username' => $username,
            'password' => $password,
            'displayName' => $displayName,
        ]);
    }

    public function disconnect(): mixed
    {
        return $this->callNative('OpenVpn.Disconnect');
    }

    public function getStatus(): mixed
    {
        return $this->callNative('OpenVpn.GetStatus');
    }

    public function getEvents(int $sinceTs = 0, int $limit = 100): mixed
    {
        return $this->callNative('OpenVpn.GetEvents', [
            'sinceTs' => max(0, $sinceTs),
            'limit' => max(1, min(200, $limit)),
        ]);
    }
}
