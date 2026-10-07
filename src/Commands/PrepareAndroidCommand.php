<?php

declare(strict_types=1);

namespace Projectmata\MobileOpenVpn\Commands;

use Native\Mobile\Plugins\Commands\NativePluginHookCommand;
use Projectmata\MobileOpenVpn\Build\AndroidEngineInstaller;
use RuntimeException;

final class PrepareAndroidCommand extends NativePluginHookCommand
{
    protected $signature = 'nativephp:mobile-openvpn:prepare-android';

    protected $description = 'Install the bundled OpenVPN runtime and configure the native Android build';

    public function handle(): int
    {
        if (!$this->isAndroid()) {
            return self::SUCCESS;
        }
        try {
            (new AndroidEngineInstaller())->install($this->buildPath(), $this->pluginPath());
        } catch (RuntimeException $error) {
            $this->error($error->getMessage());
            // NativePHP 3 catches Exception and can continue after failed hooks.
            // An Error aborts that older runner as well as NativePHP 4.
            throw new \Error('OpenVPN build preparation failed: '.$error->getMessage(), previous: $error);
        }
        $this->info('Bundled OpenVPN engine installed; no separate checkout or Gradle setup required.');
        return self::SUCCESS;
    }
}
