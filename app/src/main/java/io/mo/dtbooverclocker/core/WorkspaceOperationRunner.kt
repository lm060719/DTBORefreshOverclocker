package io.mo.dtbooverclocker.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serialize disk changes together with their UI-state commit, not just individual file writes. */
class WorkspaceOperationRunner {
    private val mutex = Mutex()

    suspend fun run(action: suspend () -> Unit) {
        mutex.withLock {
            // Once admitted, finish the commit/rollback and its state update even if the owner closes.
            withContext(NonCancellable) { action() }
        }
    }
}
