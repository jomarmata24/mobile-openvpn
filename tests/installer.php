<?php

declare(strict_types=1);

require dirname(__DIR__).'/src/Build/AndroidEngineInstaller.php';

use Projectmata\MobileOpenVpn\Build\AndroidEngineInstaller;

$root = sys_get_temp_dir().'/projectmata-openvpn-test-'.bin2hex(random_bytes(6));
mkdir($root.'/app/src/main', 0777, true);
file_put_contents($root.'/settings.gradle.kts', 'include(":app")'.PHP_EOL);
file_put_contents($root.'/app/build.gradle.kts', "plugins { id(\"com.android.application\") }\nandroid { compileSdk = 36 }\n");
file_put_contents($root.'/app/src/main/AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="My App" /></manifest>');

function check(bool $condition, string $message): void
{
    if (!$condition) {
        throw new RuntimeException($message);
    }
    echo 'PASS '.$message.PHP_EOL;
}

try {
    $installer = new AndroidEngineInstaller();
    $package = dirname(__DIR__);
    $installer->install($root, $package);
    check(is_file($root.'/app/libs/projectmata-openvpn.aar'), 'engine is installed without external checkout');
    $first = file_get_contents($root.'/app/build.gradle.kts');
    check(str_contains($first, 'projectmata-openvpn.gradle.kts'), 'app applies managed Gradle wiring');
    $script = file_get_contents($root.'/app/projectmata-openvpn.gradle.kts');
    check(str_contains($first, 'useLegacyPackaging = true'), 'native executable extraction is configured in the host Android DSL');
    $installer->install($root, $package);
    check(file_get_contents($root.'/app/build.gradle.kts') === $first, 'repeated installation is idempotent');
    file_put_contents($root.'/app/build.gradle.kts', $first."\ndependencies { implementation(project(\":openvpn:main\")) }\n");
    $installer->install($root, $package);
    check(!str_contains(file_get_contents($root.'/app/build.gradle.kts'), 'implementation(project(":openvpn:main"))'), 'legacy dependency replaced without duplicate engine classes');
    file_put_contents($root.'/app/build.gradle.kts', str_replace('compileSdk = 36', 'compileSdk = 32', $first));
    try {
        $installer->install($root, $package);
        throw new RuntimeException('old SDK silently accepted');
    } catch (RuntimeException $error) {
        check(str_contains($error->getMessage(), 'compileSdk'), 'unsupported SDK is rejected');
    }
    file_put_contents($root.'/app/build.gradle.kts', $first);
    mkdir($root.'/broken-package/resources/android/engine', 0777, true);
    file_put_contents($root.'/broken-package/resources/android/engine/runtime.json', '{invalid');
    try {
        $installer->install($root, $root.'/broken-package');
        throw new RuntimeException('invalid metadata silently accepted');
    } catch (RuntimeException $error) {
        check(str_contains($error->getMessage(), 'metadata'), 'invalid metadata uses the controlled fatal-hook error path');
    }
    file_put_contents($root.'/app/src/main/AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:name="custom.Application" /></manifest>');
    try {
        $installer->install($root, $package);
        throw new RuntimeException('custom Application silently accepted');
    } catch (RuntimeException $error) {
        check(str_contains($error->getMessage(), 'Application'), 'custom Application conflict is rejected');
    }
} finally {
    $iterator = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($root, FilesystemIterator::SKIP_DOTS), RecursiveIteratorIterator::CHILD_FIRST);
    foreach ($iterator as $entry) {
        $entry->isDir() ? rmdir($entry->getPathname()) : unlink($entry->getPathname());
    }
    rmdir($root);
}
