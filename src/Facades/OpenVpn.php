<?php

namespace Projectmata\MobileOpenVpn\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array isSupported()
 * @method static array requestPermission()
 * @method static array connect(string $profile, ?string $username = null, ?string $password = null, ?string $displayName = null)
 * @method static array disconnect()
 * @method static array getStatus()
 * @method static array getEvents(int $sinceTs = 0, int $limit = 100)
 */
class OpenVpn extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return 'mobile-openvpn';
    }
}
