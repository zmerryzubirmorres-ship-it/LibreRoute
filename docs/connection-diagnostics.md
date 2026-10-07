# Диагностика подключения Android

Источник истины для пользовательских и административных состояний:
[корневой индекс документации](../../docs/README.md).

Диагностика должна различать:

1. **Transport online** — провайдер принял соединение.
2. **Peer ready** — контрольная проба прошла через тот же транспорт и вернулась.
3. **Internet ready** — DNS/TCP-проверка через туннель завершилась успешно.

Состояние transport online само по себе не доказывает готовность peer или
доступность интернета. UI показывает только значения, полученные от реального
health-check, и предлагает одно соответствующее действие. CAPTCHA/Yandex
переводится в WebView; MQTT и Jitsi сетевые ошибки WebView не открывают.

Для установки сервера и протоколов используются отдельные состояния
`probing`, `installing`, `needs_action`, `unknown`, `ready`, `failed` и
`rolled_back`; их обработка описана в корневых ТЗ.

Yandex WebView открывается только по явному действию «Открыть проверку» и при
совпадении выбранного document URL. Возврат Activity, временный HTTP 429/5xx,
DNS/timeout и peer health не открывают браузер и не запускают auth retry. При
действительной auth/CAPTCHA-проблеме VPN reconnect останавливается до ручного
повтора. User-Agent и cookies должны совпадать с браузерной сессией во всех
последующих Core HTTP/WebSocket запросах.

## MQTT health race (2026-10-05)

`CONNACK` и `SUBACK` подтверждают готовность MQTT provider, но не peer health. До provider readiness health probe не создаётся; после readiness ticker отправляет probe и ждёт pong. При reconnect stale health сбрасывается. Для диагностики используются role/event/nonce fingerprint без raw nonce, ключей и payload. Ожидаемые строки: `mqtt: broker confirmed inbound subscription`, затем `[HEALTH] probe sent`, `[HEALTH] pong sent`, `[HEALTH] peer ready`.
