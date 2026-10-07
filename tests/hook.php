<?php

declare(strict_types=1);

// Exercise real Laravel command auto-registration using a host's installed
// NativePHP version, without reinstalling this package into that host.
$host = realpath($argv[1] ?? dirname(__DIR__, 4));
$package = dirname(__DIR__);
require $host.'/vendor/autoload.php';
require $package.'/src/MobileOpenVpnServiceProvider.php';
require $package.'/src/Build/AndroidEngineInstaller.php';
require $package.'/src/Commands/PrepareAndroidCommand.php';

$app = require $host.'/bootstrap/app.php';
$app->register(Projectmata\MobileOpenVpn\MobileOpenVpnServiceProvider::class);
$kernel = $app->make(Illuminate\Contracts\Console\Kernel::class);
$kernel->bootstrap();

$root = sys_get_temp_dir().'/projectmata-openvpn-hook-'.bin2hex(random_bytes(6));
mkdir($root.'/app/src/main', 0777, true);
file_put_contents($root.'/settings.gradle.kts', 'include(":app")');
file_put_contents($root.'/app/build.gradle.kts', "plugins { id(\"com.android.application\") }\nandroid { compileSdk = 36 }\n");
file_put_contents($root.'/app/src/main/AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application /></manifest>');

try {
    $exit = Illuminate\Support\Facades\Artisan::call('nativephp:mobile-openvpn:prepare-android', [
        '--platform' => 'android', '--build-path' => $root, '--plugin-path' => $package,
    ]);
    if ($exit !== 0 || !is_file($root.'/app/libs/projectmata-openvpn.aar')) {
        throw new RuntimeException('Registered NativePHP hook failed to install the engine.');
    }
    echo 'PASS hook registers and installs runtime using host '.$host.PHP_EOL;
} finally {
    $iterator = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($root, FilesystemIterator::SKIP_DOTS), RecursiveIteratorIterator::CHILD_FIRST);
    foreach ($iterator as $entry) {
        $entry->isDir() ? rmdir($entry->getPathname()) : unlink($entry->getPathname());
    }
    rmdir($root);
}
