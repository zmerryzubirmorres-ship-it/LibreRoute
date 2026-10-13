package io.github.libreroute.admin

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSetupLifecycleTest {
    private val node = ServerNode(id = "rotation-node", name = "Test", host = "192.0.2.10")

    @Test fun freshApplicationNeverReinstallsDetectedCore() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            for (inventory in listOf(
                ServerInventory(arch = "x86_64", isCompatible = true, managedNamespaces = listOf("existing-node")),
                ServerInventory(arch = "x86_64", isCompatible = true, installedLibreRouteVersion = "1.2.43")
            )) {
                val store = ViewModelStore()
                try {
                    val plans = AtomicInteger(); val installs = AtomicInteger()
                    val backend = object : ServerInstallationBackend {
                        override fun plan(node: ServerNode, inventory: ServerInventory, operationId: String): JsonObject {
                            plans.incrementAndGet(); error("Existing Core must not receive an installation plan")
                        }
                        override fun install(node: ServerNode, inventory: ServerInventory, operationId: String,
                            documentUrl: String, join: AdminEnvelope, pairingCode: String, expectedPlan: JsonObject,
                            ownerRecovery: Boolean): JsonObject { installs.incrementAndGet(); error("No reinstall") }
                        override fun status(node: ServerNode, operationId: String): JsonObject = error("No operation")
                        override fun rollback(node: ServerNode, operationId: String): JsonObject = error("No rollback")
                    }
                    val repo = AdminRepository(storage = InMemoryAdminStorage(), installationBackend = backend,
                        serverInventoryProbe = { inventory })
                    repo.addServerNode(node)
                    val vm = ServerSetupViewModel(Application(), repo); store.put("setup", vm)
                    vm.initialize(node.id, automatic = true); vm.start()
                    withTimeout(5000) { vm.state.first { !it.busy } }
                    assertEquals(AdminRole.NONE, repo.getRole(node.id))
                    repeat(3) { vm.check() }
                    assertEquals("check", vm.state.value.stage)
                    assertFalse(vm.state.value.busy)
                    assertEquals(0, plans.get()); assertEquals(0, installs.get())
                    assertNull(repo.latestServerInstallation(node.id))
                    assertEquals(inventory.managedNamespaces.singleOrNull(), vm.node?.managementNamespace)
                } finally { store.clear() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun continueExceptionReturnsToResultAndPreservesOperation() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val storage = InMemoryAdminStorage()
            val operation = ServerInstallOperation("saved-operation", node.id, "namespace", node,
                "{}", "plan-hash", "owner", "device", stage = ServerInstallOperation.UNKNOWN,
                serverStage = "prepared")
            storage.putString("admin_install_operations", Json.encodeToString(listOf(operation)))
            val repo = AdminRepository(storage = storage); repo.addServerNode(node)
            val vm = ServerSetupViewModel(Application(), repo); store.put("setup", vm)
            vm.restoreCheckpoint(SetupCheckpoint(operation.operationId, AdminChannel.DIRECT_SSH,
                "", "", false, SetupState("result")))
            vm.initialize(node.id); vm.continueUpdate()
            withTimeout(5000) { vm.state.first { !it.busy && it.error != null } }
            assertEquals(operation.operationId, repo.latestServerInstallation(node.id)?.operationId)
            assertEquals(AdminChannel.DIRECT_SSH, repo.latestServerInstallation(node.id)?.channel)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun recreationKeepsRunningJobChannelIdentityAndProgress() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val probes = AtomicInteger(); val store = ViewModelStore()
        try {
            val repo = AdminRepository(serverInventoryProbe = {
                probes.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ServerInventory(arch = "x86_64", isCompatible = true)
            })
            repo.addServerNode(node)
            val vm = ServerSetupViewModel(Application(), repo)
            store.put("setup", vm)
            vm.initialize(node.id)
            vm.selectUpdateChannel(AdminChannel.DIRECT_SSH)
            vm.start()
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val running = vm.state.value
            assertTrue(running.busy)
            // The Activity calls these again after rotation, using a stale Bundle/Intent.
            vm.restore(Bundle())
            vm.restoreCheckpoint(SetupCheckpoint("foreign-operation", AdminChannel.DOCUMENT,
                "foreign-document", "other-profile", true, SetupState("install", true, "Old progress")))
            vm.initialize("foreign-node", recovery = true, automatic = true)
            vm.start(); vm.check(); vm.updateCore(); vm.continueUpdate(); vm.rollbackUpdate()
            vm.selectUpdateChannel(AdminChannel.DOCUMENT)
            assertEquals(running, vm.state.value)
            assertEquals(node.id, vm.node?.id)
            assertEquals(AdminChannel.DIRECT_SSH, vm.updateChannel)
            assertEquals(1, probes.get())
            release.countDown()
            withTimeout(5000) { vm.state.first { !it.busy } }
            assertNull(vm.state.value.error)
            assertEquals(1, probes.get())
        } finally { release.countDown(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun newProcessChecksOriginalOperationOnceAndNeverResubmitsUpload() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val storage = InMemoryAdminStorage()
            val operation = ServerInstallOperation("original-operation", node.id, "namespace", node,
                "{}", "plan-hash", "owner-profile", "device", stage = ServerInstallOperation.SUBMITTING)
            storage.putString("admin_install_operations", Json.encodeToString(listOf(operation)))
            val reads = AtomicInteger(); val mutations = AtomicInteger()
            val backend = object : ServerInstallationBackend {
                override fun plan(node: ServerNode, inventory: ServerInventory, operationId: String): JsonObject {
                    mutations.incrementAndGet(); error("No new plans")
                }
                override fun install(node: ServerNode, inventory: ServerInventory, operationId: String,
                    documentUrl: String, join: AdminEnvelope, pairingCode: String, expectedPlan: JsonObject,
                    ownerRecovery: Boolean): JsonObject { mutations.incrementAndGet(); error("No upload replay") }
                override fun status(node: ServerNode, operationId: String): JsonObject {
                    assertEquals("original-operation", operationId); assertEquals(this@ServerSetupLifecycleTest.node.id, node.id)
                    reads.incrementAndGet(); error("Connection temporarily unavailable")
                }
                override fun rollback(node: ServerNode, operationId: String): JsonObject {
                    mutations.incrementAndGet(); error("No automatic rollback")
                }
            }
            val repo = AdminRepository(storage = storage, installationBackend = backend,
                serverInventoryProbe = { error("Use the stored operation") })
            repo.addServerNode(node)
            val vm = ServerSetupViewModel(Application(), repo); store.put("setup", vm)
            vm.restoreCheckpoint(SetupCheckpoint(operation.operationId, AdminChannel.ACTIVE_TRANSPORT,
                "", "Owner", false, SetupState("install", true, "52%")))
            vm.initialize(node.id); vm.start()
            withTimeout(5000) { vm.state.first { !it.busy && it.error != null } }
            vm.start()
            assertEquals(1, reads.get()); assertEquals(0, mutations.get())
            assertEquals(AdminChannel.ACTIVE_TRANSPORT, vm.updateChannel)
            assertEquals(operation.operationId, repo.latestServerInstallation(node.id)?.operationId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
