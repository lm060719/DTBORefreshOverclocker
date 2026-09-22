package io.mo.dtbooverclocker.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceOperationRunnerTest {
    @Test
    fun concurrentEditsReadTheLatestStateAndKeepEveryTransaction() = runTest {
        val runner = WorkspaceOperationRunner()
        var transactions = emptyList<Int>()
        var diskValue = 0
        repeat(12) { number ->
            launch {
                runner.run {
                    val before = transactions
                    val value = diskValue
                    delay(10)
                    diskValue = value + 1
                    transactions = before + number
                }
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(12, diskValue)
        assertEquals((0..11).toList(), transactions)
    }

    @Test
    fun cancellationAfterAdmissionStillFinishesStateCommit() = runTest {
        val runner = WorkspaceOperationRunner()
        val written = CompletableDeferred<Unit>()
        var diskValue = 0
        var stateValue = 0
        val job = launch {
            runner.run {
                diskValue = 1
                written.complete(Unit)
                delay(10)
                stateValue = diskValue
            }
        }
        written.await()
        job.cancelAndJoin()
        assertEquals(1, stateValue)
        runner.run { stateValue++ }
        assertEquals(2, stateValue)
    }
}
