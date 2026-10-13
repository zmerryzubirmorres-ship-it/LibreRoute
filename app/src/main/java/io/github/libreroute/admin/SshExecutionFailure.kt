package io.github.libreroute.admin

/** Classify native stderr without exposing server output, addresses or credentials. */
internal data class SshExecutionFailure(val code: String, val message: String, val beforeSend: Boolean = false)

internal fun sshExecutionFailure(exitCode: Int, stderr: String, timeoutSeconds: Long): SshExecutionFailure {
    if (exitCode == -1) return SshExecutionFailure("process_timeout",
        "SSH: сервер не ответил за $timeoutSeconds с. Проверьте состояние обновления: результат команды неизвестен.")
    if (exitCode == -2) return SshExecutionFailure("response_too_large",
        "SSH: ответ превышает допустимый размер. Проверьте состояние обновления.")
    val error = stderr.lowercase(java.util.Locale.ROOT)
    // Only a failed dial proves that ExecuteEnvelope never sent the command.
    val dial = error.contains("ssh administrative request failed: ssh dial ")
    if (error.contains("host key") || error.contains("hostkey")) return SshExecutionFailure("host_key",
        "SSH: ключ сервера не прошёл проверку. Проверьте сохранённые сведения о сервере.")
    if (error.contains("unable to authenticate") || error.contains("no supported methods remain")) return SshExecutionFailure("authentication",
        "SSH: сервер не принял ключ доступа. Проверьте настройки подключения.")
    if (error.contains("private key") || error.contains("passphrase")) return SshExecutionFailure("private_key",
        "SSH: не удалось прочитать ключ доступа. Проверьте настройки подключения.")
    val transient = when {
        error.contains("i/o timeout") || error.contains("connection timed out") -> "connect_timeout"
        error.contains("connection reset") || error.contains("unexpected eof") || error.endsWith(": eof") -> "connect_interrupted"
        error.contains("connection refused") -> "connect_refused"
        error.contains("network is unreachable") || error.contains("no route to host") -> "network_unreachable"
        else -> null
    }
    if (dial && transient != null) return SshExecutionFailure(transient,
        when (transient) {
            "connect_timeout" -> "SSH: истекло время подключения к серверу. Запрос не отправлен. Можно продолжить обновление."
            "connect_refused" -> "SSH: сервер отказал в подключении. Запрос не отправлен. Проверьте доступность SSH."
            "network_unreachable" -> "SSH: сеть сервера недоступна. Запрос не отправлен. Проверьте соединение."
            else -> "SSH: соединение прервалось до отправки запроса. Можно продолжить обновление."
        }, beforeSend = true)
    return SshExecutionFailure("unknown_result",
        "SSH: команда не завершена (код $exitCode). Проверьте состояние обновления: результат команды неизвестен.")
}
