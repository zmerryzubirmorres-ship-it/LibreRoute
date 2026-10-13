package io.github.libreroute.ui

import androidx.lifecycle.ViewModelStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
class ScreenOperationLifecycleTest {
    @Test fun recreationDoesNotRepeatWorkAndResultWaitsForNewScreen() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        val release = CountDownLatch(1)
        try {
            val entered = CountDownLatch(1)
            val calls = AtomicInteger()
            val vm = ScreenOperationViewModel(); store.put("operation", vm)
            vm.run { calls.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "done" }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val recreated = store["operation"] as ScreenOperationViewModel
            recreated.run { calls.incrementAndGet(); "duplicate" }
            assertTrue(recreated.state.value.running)
            release.countDown()
            val result = withTimeout(5000) { recreated.state.first { it.result != null } }
            // A stopped/destroyed view does not consume the terminal result.
            assertEquals("done", result.result?.getOrThrow())
            recreated.run { calls.incrementAndGet(); "duplicate after completion" }
            assertEquals(1, calls.get())
            recreated.consumed()
            assertNull(recreated.state.value.result)
            assertFalse(recreated.state.value.running)
        } finally { release.countDown(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun failureUnlocksOnlyAfterItsResultWasDelivered() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val vm = ScreenOperationViewModel(); store.put("operation", vm)
            vm.run { error("server rejected") }
            val failed = withTimeout(5000) { vm.state.first { it.result != null } }
            assertFalse(failed.running)
            assertEquals("server rejected", failed.result?.exceptionOrNull()?.message)
            vm.consumed()
            vm.run { "retry confirmed" }
            assertEquals("retry confirmed", withTimeout(5000) { vm.state.first { it.result != null } }.result?.getOrThrow())
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
