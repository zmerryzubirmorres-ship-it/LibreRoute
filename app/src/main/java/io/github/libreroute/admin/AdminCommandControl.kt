package io.github.libreroute.admin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Tracks synchronous repository calls, including nested signed access probes. */
class AdminCommandControl {
    enum class Phase(val label: String) {
        CHECKING("Проверяем доступ…"), FORMING("Формируем команду…"), SENDING("Выполняем…")
    }
    data class Action(val id: String, val serverId: String?, val operation: String, val phase: Phase,
                      val blocksUi: Boolean = true, val detail: String? = null)
    private val mutable = MutableStateFlow<List<Action>>(emptyList())
    val actions = mutable.asStateFlow()
    private data class Frame(val id: String, var phase: Phase = Phase.CHECKING, var depth: Int = 1,
                             val workflow: Boolean = false, val visibleId: String = id)
    private val current = ThreadLocal<Frame>()
    private val mutationLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun beforeSubmission(): Boolean = current.get()?.phase != Phase.SENDING

    /** One user action can contain several signed commands; retain its visible progress between them. */
    fun stage(detail: String) {
        val frame = current.get() ?: return
        synchronized(this) {
            mutable.value = mutable.value.map { if (it.id == frame.visibleId) it.copy(detail = detail) else it }
        }
    }

    fun phase(phase: Phase) {
        val frame = current.get() ?: return
        // A nested snapshot is the access probe, not submission of the parent command.
        if (frame.depth != 1) return
        frame.phase = phase
        synchronized(this) {
            mutable.value = mutable.value.map { if (it.id == frame.id) it.copy(phase = phase) else it }
        }
    }

    fun <T> run(serverId: String?, operation: String, mutation: Boolean,
                cancelled: (String, Throwable) -> Unit, workflow: Boolean = false, work: () -> T): T {
        current.get()?.let { parent ->
            // Each command in a workflow owns its submission state. Its nested access
            // probes still cannot mark that command as sent.
            if (parent.workflow) {
                val command = Frame(UUID.randomUUID().toString(), visibleId = parent.visibleId)
                current.set(command)
                try { return work() } catch (error: Throwable) {
                    if (command.phase != Phase.SENDING) cancelled(command.id, error)
                    throw error
                } finally { current.set(parent) }
            }
            parent.depth++
            try { return work() } finally { parent.depth-- }
        }
        val frame = Frame(UUID.randomUUID().toString(), workflow = workflow)
        val lock = if (mutation) mutationLocks.getOrPut(serverId ?: "legacy") { ReentrantLock() } else null
        if (lock != null && !lock.tryLock()) {
            val error = IllegalStateException("Другая команда управления уже выполняется. Дождитесь результата")
            cancelled(frame.id, error)
            throw error
        }
        current.set(frame)
        synchronized(this) {
            mutable.value = mutable.value + Action(frame.id, serverId, operation, frame.phase, blocksUi = mutation)
        }
        try {
            return work()
        } catch (error: Throwable) {
            if (frame.phase != Phase.SENDING) cancelled(frame.id, error)
            throw error
        } finally {
            synchronized(this) { mutable.value = mutable.value.filterNot { it.id == frame.id } }
            current.remove()
            lock?.unlock()
        }
    }
}
