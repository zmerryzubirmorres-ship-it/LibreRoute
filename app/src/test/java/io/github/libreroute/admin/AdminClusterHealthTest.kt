package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test

class AdminClusterHealthTest {
    private val ready = AdminWorkerStatus(serverId = "one", desiredRevision = 4, appliedRevision = 4, readiness = true)
    private fun snapshot(status: AdminWorkerStatus? = ready, complete: Boolean = true) = AdminClusterSnapshot(
        servers = listOf("one"), isComplete = complete,
        perServerStatus = status?.let { mapOf("one" to it) }.orEmpty())

    @Test fun oneReadyServerDoesNotImplyOtherServers() {
        assertEquals(AdminClusterHealth(true, "Конфигурация и маршруты сервера готовы"), adminClusterHealth(snapshot()))
    }
    @Test fun oneUnconfirmedServerNamesTheMissingConfirmation() {
        assertEquals(AdminClusterHealth(false, "Сервер ещё не подтвердил применение конфигурации"),
            adminClusterHealth(snapshot(ready.copy(appliedRevision = 3))))
        assertEquals(AdminClusterHealth(false, "Готовность маршрутов сервера ещё не подтверждена"),
            adminClusterHealth(snapshot(ready.copy(readiness = false))))
    }
    @Test fun absentStatusIsNotReportedAsReady() {
        assertEquals(AdminClusterHealth(false, "Сервер ещё не сообщил состояние рабочих маршрутов"), adminClusterHealth(snapshot(null)))
        assertFalse(adminClusterHealth(AdminClusterSnapshot()).confirmed)
    }
    @Test fun zeroRevisionsDoNotProveAppliedConfiguration() {
        assertFalse(adminClusterHealth(snapshot(ready.copy(desiredRevision = 0, appliedRevision = 0))).confirmed)
    }
    @Test fun workerErrorRemainsVisibleEvenWithReadyFlag() {
        assertEquals(AdminClusterHealth(false, "Сервер сообщил об ошибке рабочих маршрутов"),
            adminClusterHealth(snapshot(ready.copy(lastError = "worker failed"))))
    }
    @Test fun incompleteSnapshotIsNotReportedAsReady() {
        assertEquals(AdminClusterHealth(false, "Сведения о сервере получены не полностью"), adminClusterHealth(snapshot(complete = false)))
    }
    @Test fun multipleServersCountMissingStatusesAsUnconfirmed() {
        val partial = snapshot().copy(servers = listOf("one", "two"))
        assertEquals(AdminClusterHealth(false, "Готовность подтверждена для 1 из 2 серверов"), adminClusterHealth(partial))
        assertTrue(adminClusterHealth(partial.copy(perServerStatus = mapOf("one" to ready, "two" to ready.copy(serverId = "two")))).confirmed)
    }
}
