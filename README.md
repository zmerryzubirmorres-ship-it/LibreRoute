# LibreRoute Android

Android-клиент LibreRoute. Актуальные продуктовые требования находятся в
[корневом индексе документации](../docs/README.md).

Сценарии серверов и протоколов описаны там же; Android-specific инструкции
сборки и диагностики находятся в [docs/](docs/README.md).

Проверка сборки:

```text
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

После изменения Core сначала пересоберите его бинарники:

```powershell
./build-libreroute-core.ps1 -NdkRoot '<Android SDK>/ndk/<version>'
../LibreRoute-Core/scripts/build-linux-and-manifests.ps1
./test-core-artifacts.ps1
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Build scripts используют локальный installation trust key; Android принимает
путь к его публичной части через `-InstallTrustKeyFile`. Секретный ключ остаётся
вне репозитория. `core-artifacts.json` записывается сборочными скриптами:
при несовпадении исходников, хешей, подписи или версии Gradle требует пересборку.
