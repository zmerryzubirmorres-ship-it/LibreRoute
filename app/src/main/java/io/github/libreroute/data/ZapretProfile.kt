package io.github.libreroute.data

import kotlinx.serialization.Serializable

/** Only options supported by the bundled no-root tpws SOCKS process. */
@Serializable
data class ZapretProfile(
    val id: String,
    val name: String,
    val split: String = "1,midsld",
    val tlsRecord: Boolean = true,
    val methodEol: Boolean = true,
    val hostCase: Boolean = false,
    val disorder: Boolean = false,
    val blockQuic: Boolean = true,
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Некорректный идентификатор профиля" }
        require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "Введите название до 80 символов" }
        require(split in SPLITS) { "Некорректный способ разделения" }
    }

    fun command(binary: String, port: Int): List<String> {
        validate()
        require(port in 1..65535)
        return buildList {
            addAll(listOf(binary, "--socks", "--bind-addr=127.0.0.1", "--port=$port", "--maxconn=1024", "--filter-tcp=80,443"))
            if (split.isNotEmpty()) add("--split-pos=$split")
            if (tlsRecord) add("--tlsrec=sni")
            if (methodEol) add("--methodeol")
            if (hostCase) add("--hostcase")
            if (disorder && split.isNotEmpty()) add("--disorder")
        }
    }

    companion object {
        val SPLITS = listOf("1,midsld", "1", "midsld", "")
        val DEFAULT = ZapretProfile("standard", "Стандартный")
    }
}

@Serializable
data class ZapretProfileState(
    val profiles: List<ZapretProfile> = listOf(ZapretProfile.DEFAULT),
    val activeId: String = ZapretProfile.DEFAULT.id,
    // Full snapshot permits rollback even after editing the active profile.
    val previous: ZapretProfile? = null,
) {
    val active: ZapretProfile get() = profiles.first { it.id == activeId }

    fun validate() {
        require(profiles.size in 1..32) { "Можно сохранить не более 32 профилей" }
        profiles.forEach { it.validate() }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Повторяющиеся профили" }
        require(profiles.singleOrNull { it.id == ZapretProfile.DEFAULT.id } == ZapretProfile.DEFAULT) { "Стандартный профиль должен быть сохранён" }
        require(profiles.any { it.id == activeId }) { "Активный профиль не найден" }
        previous?.validate()
        require(previous == null || profiles.any { it.id == previous.id }) { "Предыдущий профиль не найден" }
        require(previous?.id != ZapretProfile.DEFAULT.id || previous == ZapretProfile.DEFAULT)
    }

    fun save(profile: ZapretProfile): ZapretProfileState {
        validate()
        profile.validate()
        require(profile.id != ZapretProfile.DEFAULT.id) { "Создайте копию стандартного профиля" }
        val exists = profiles.any { it.id == profile.id }
        val updated = if (exists) profiles.map { if (it.id == profile.id) profile else it } else profiles + profile
        return copy(profiles = updated, previous = if (profile.id == activeId && profile != active) active else previous)
            .also { it.validate() }
    }

    fun select(id: String): ZapretProfileState {
        validate()
        require(profiles.any { it.id == id }) { "Профиль не найден" }
        return if (id == activeId) this else copy(activeId = id, previous = active)
    }

    fun rollback(): ZapretProfileState {
        validate()
        val target = previous ?: return this
        val updated = if (profiles.any { it.id == target.id }) profiles.map { if (it.id == target.id) target else it } else profiles + target
        return copy(profiles = updated, activeId = target.id, previous = active).also { it.validate() }
    }

    fun delete(id: String): ZapretProfileState {
        validate()
        require(id != ZapretProfile.DEFAULT.id && id != activeId) { "Нельзя удалить стандартный или выбранный профиль" }
        // An explicitly deleted profile must not reappear through rollback.
        return copy(profiles = profiles.filterNot { it.id == id }, previous = previous?.takeUnless { it.id == id })
    }
}
