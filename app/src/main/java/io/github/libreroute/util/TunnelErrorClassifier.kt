package io.github.libreroute.util

enum class ErrorCategory {
    PORT_IN_USE,
    CONNECTION_DROPPED,
    TIMEOUT,
    AUTH_FAILED,
    /** MQTT broker cannot be reached at all (DNS, refused connection, offline). */
    MQTT_BROKER_UNAVAILABLE,
    /** MQTT WebSocket/TLS negotiation failed before MQTT authentication. */
    MQTT_TLS_FAILED,
    /** MQTT credentials or ACL rejected the client. */
    MQTT_AUTH_FAILED,
    /** The MQTT peer has not advertised readiness yet. */
    MQTT_PEER_NOT_READY,
    /** Jitsi requires the room moderator/organizer to prepare the room. */
    JITSI_MODERATOR_REQUIRED,
    /** The organizer JWT was rejected because its exp has elapsed. */
    JITSI_TOKEN_EXPIRED,
    MALFORMED_DATA,
    NO_NETWORK,
    UNKNOWN
}

data class ClassifiedError(
    val title: String,
    val message: String,
    val category: ErrorCategory,
    val isRecoverable: Boolean = true
)

object TunnelErrorClassifier {

    fun classify(raw: String?): ClassifiedError {
        if (raw.isNullOrBlank()) {
            return ClassifiedError(
                title = "Ошибка подключения",
                message = "Соединение было разорвано по неизвестной причине.",
                category = ErrorCategory.UNKNOWN
            )
        }

        val lower = raw.lowercase()

        return when {
            // Keep service-specific causes ahead of generic timeout/auth/drop rules.  The
            // UI can then offer the right recovery action and never mistake MQTT for a
            // CAPTCHA/WebView flow.
            lower.contains("jitsi") && (lower.contains("token-expired") || lower.contains("token expired") || lower.contains("jwt expired")) -> {
                ClassifiedError(
                    title = "Токен Jitsi истёк",
                    message = "Организатору нужно снова войти в комнату через браузер и проверить подключение.",
                    category = ErrorCategory.JITSI_TOKEN_EXPIRED
                )
            }
            lower.contains("jitsi") && (lower.contains("moderator-required") || lower.contains("moderator required") ||
                lower.contains("room not started") || lower.contains("moderator required")) -> {
                ClassifiedError(
                    title = "Нужен организатор комнаты Jitsi",
                    message = "Попросите администратора запустить комнату, затем проверьте подключение ещё раз.",
                    category = ErrorCategory.JITSI_MODERATOR_REQUIRED
                )
            }
            lower.contains("mqtt") && (lower.contains("acl") || lower.contains("not authorized") ||
                lower.contains("not authorised") || lower.contains("mqtt: auth") || lower.contains("mqtt auth")) -> {
                ClassifiedError(
                    title = "Доступ MQTT отклонён",
                    message = "Брокер отклонил авторизацию или ACL. Проверьте профиль либо обратитесь к администратору.",
                    category = ErrorCategory.MQTT_AUTH_FAILED
                )
            }
            lower.contains("mqtt") && (lower.contains("tls") || lower.contains("x509") ||
                lower.contains("certificate") || lower.contains("websocket handshake")) -> {
                ClassifiedError(
                    title = "Ошибка WSS/TLS MQTT",
                    message = "Не удалось установить защищённое соединение с MQTT-брокером. Проверьте адрес и сеть.",
                    category = ErrorCategory.MQTT_TLS_FAILED
                )
            }
            lower.contains("mqtt") && (lower.contains("peer not ready") || lower.contains("peer-ready") ||
                lower.contains("waiting for peer") || lower.contains("peer health")) -> {
                ClassifiedError(
                    title = "Узел MQTT ещё не готов",
                    message = "Удалённый узел не подтвердил готовность. Повторите проверку позже.",
                    category = ErrorCategory.MQTT_PEER_NOT_READY
                )
            }
            lower.contains("mqtt") && (lower.contains("connection refused") || lower.contains("broker unavailable") ||
                lower.contains("dial tcp") || lower.contains("no such host") || lower.contains("dns")) -> {
                ClassifiedError(
                    title = "MQTT-брокер недоступен",
                    message = "Не удалось подключиться к брокеру MQTT. Проверьте сеть или повторите попытку.",
                    category = ErrorCategory.MQTT_BROKER_UNAVAILABLE
                )
            }
            lower.contains("eaddrinuse") || lower.contains("address already in use") || lower.contains("bind: address") -> {
                ClassifiedError(
                    title = "Конфликт портов",
                    message = "Локальный порт занят другим процессом. LibreRoute автоматически перевыделит свободный порт при повторном подключении.",
                    category = ErrorCategory.PORT_IN_USE
                )
            }
            lower.contains("timeout") || lower.contains("timed out") || lower.contains("deadline exceeded") || lower.contains("transport startup waiting") -> {
                ClassifiedError(
                    title = "Таймаут подключения",
                    message = "Сервер туннеля не ответил вовремя. Возможно, сетевой адрес заблокирован или перегружен.",
                    category = ErrorCategory.TIMEOUT
                )
            }
            lower.contains("broken pipe") || lower.contains("connection reset") || lower.contains("close 1005") ||
            lower.contains("connection closed") || lower.contains("ws closed") || lower.contains("eof") -> {
                ClassifiedError(
                    title = "Обрыв соединения",
                    message = "Связь с сервером туннеля была прервана провайдером или удаленным узлом. Рекомендуется повторить подключение.",
                    category = ErrorCategory.CONNECTION_DROPPED
                )
            }
            lower.contains("handshake failed") || lower.contains("auth failed") || lower.contains("unauthorized") ||
            lower.contains("invalid key") || lower.contains("bad mac") || lower.contains("certificate") -> {
                ClassifiedError(
                    title = "Ошибка аутентификации",
                    message = "Не удалось подтвердить ключ шифрования или токен доступа. Проверьте правильность параметров туннеля.",
                    category = ErrorCategory.AUTH_FAILED
                )
            }
            lower.contains("panic:") || lower.contains("slice bounds") || lower.contains("runtime error") ||
            lower.contains("invalid wire type") || lower.contains("corrupted") -> {
                ClassifiedError(
                    title = "Сбой протокола",
                    message = "Получен некорректный сетевой пакет от сервера. Попробуйте обновить конфигурацию или сменить канал.",
                    category = ErrorCategory.MALFORMED_DATA
                )
            }
            lower.contains("no route to host") || lower.contains("network unreachable") || lower.contains("waitingfornetwork") ||
            lower.contains("нет сети") -> {
                ClassifiedError(
                    title = "Отсутствует подключение к сети",
                    message = "Устройство не подключено к интернету. Проверьте мобильную сеть или Wi-Fi.",
                    category = ErrorCategory.NO_NETWORK
                )
            }
            else -> {
                val clean = raw.lines().firstOrNull { it.isNotBlank() }?.take(160) ?: raw
                ClassifiedError(
                    title = "Ошибка туннеля",
                    message = clean,
                    category = ErrorCategory.UNKNOWN
                )
            }
        }
    }
}
