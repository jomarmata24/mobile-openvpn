<?php

namespace Projectmata\MobileOpenVpn\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array isSupported()
 * @method static array requestPermission()
 * @method static array connect(string $profile, ?string $username = null, ?string $password = null, ?string $displayName = null)
 * @method static array disconnect()
 * @method static array getStatus()
 */
class OpenVpn extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return 'mobile-openvpn';
    }
}
