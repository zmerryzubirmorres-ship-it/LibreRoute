package io.github.libreroute.admin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JazzPolicyLifecycleTest {
    private val node = ServerNode(id = "fixture", name = "Fixture", host = "192.0.2.10")
    private val projection = JazzPoolProjection(1, JazzRoomPolicy(server_id = "fixture"))

    private fun test(block: suspend (JazzRoomPolicyViewModel) -> Unit) = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val vm = JazzRoomPolicyViewModel(SavedStateHandle()); store.put("policy", vm)
            vm.mode = "shared_pool"; vm.pairs = "2"; vm.rooms = "8"
            block(vm)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun refreshPreservesUnsavedPolicy() = test { vm ->
        vm.run(node, false) { Result.success(projection) }
        withTimeout(5000) { vm.state.first { !it.running && it.projection != null } }
        assertEquals("shared_pool", vm.mode); assertEquals("2", vm.pairs); assertEquals("8", vm.rooms)
    }

    @Test fun failedSavePreservesDraftForExplicitRetry() = test { vm ->
        vm.run(node, true) { Result.failure(IllegalStateException("offline")) }
        withTimeout(5000) { vm.state.first { it.error != null } }
        assertEquals("offline", vm.state.value.error)
        assertEquals("shared_pool", vm.mode); assertEquals("8", vm.rooms)
    }

    @Test fun confirmedSaveReplacesDraftWithServerProjection() = test { vm ->
        vm.run(node, true) { Result.success(projection) }
        withTimeout(5000) { vm.state.first { !it.running && it.projection != null } }
        assertNull(vm.mode); assertNull(vm.pairs); assertNull(vm.rooms)
        assertEquals(projection, vm.state.value.projection)
    }
}
