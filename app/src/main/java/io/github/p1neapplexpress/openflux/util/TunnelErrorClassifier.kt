package io.github.p1neapplexpress.openflux.util

enum class ErrorCategory {
    PORT_IN_USE,
    CONNECTION_DROPPED,
    TIMEOUT,
    AUTH_FAILED,
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
            lower.contains("eaddrinuse") || lower.contains("address already in use") || lower.contains("bind: address") -> {
                ClassifiedError(
                    title = "Конфликт портов",
                    message = "Локальный порт занят другим процессом. Fluxon автоматически перевыделит свободный порт при повторном подключении.",
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
