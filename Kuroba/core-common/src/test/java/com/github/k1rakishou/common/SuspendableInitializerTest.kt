package com.github.k1rakishou.common

import junit.framework.Assert.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SuspendableInitializerTest {

  @Test
  fun `test multiple awaiters`() = runBlocking {
    val suspendableInitializer = SuspendableInitializer<Unit>("test", enabledLogs = false)
    val counter = AtomicInteger(0)
    val completableDeferred = CompletableDeferred<Int>()

    repeat(100) { index ->
      launch {
        suspendableInitializer.awaitUntilInitialized()

        if (counter.incrementAndGet() >= 100) {
          completableDeferred.complete(counter.get())
        }
      }
    }

    repeat(100) {
      suspendableInitializer.initWithValue(Unit)
    }

    val resultValue = completableDeferred.await()

    assertEquals(100, resultValue)
    assertEquals(100, counter.get())
  }

}