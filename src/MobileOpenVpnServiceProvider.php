<?php

namespace Projectmata\MobileOpenVpn;

use Illuminate\Support\ServiceProvider;

class MobileOpenVpnServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton('mobile-openvpn', function () {
            return new OpenVpnManager();
        });
    }

    public function boot(): void
    {
        //
    }
}
