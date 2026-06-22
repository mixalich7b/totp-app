package net.mixalich7b.totp

sealed interface MigrationBatchResult {
    data class Pending(val received: Int, val total: Int) : MigrationBatchResult
    data class Complete(
        val entries: List<TotpEntry>,
        val issues: List<MigrationEntryIssue>,
    ) : MigrationBatchResult
}

class MigrationBatchCollector {
    private val parts = mutableMapOf<Int, MigrationBatch>()
    private var batchId: Long? = null
    private var batchSize = 0

    fun add(batch: MigrationBatch): MigrationBatchResult {
        if (batch.batchSize == 1) {
            clear()
            return MigrationBatchResult.Complete(batch.entries, batch.issues)
        }
        if (batchId != batch.batchId || batchSize != batch.batchSize) {
            clear()
            batchId = batch.batchId
            batchSize = batch.batchSize
        }
        val previous = parts.put(batch.batchIndex, batch)
        if (previous !== batch) previous?.wipe()
        if (parts.size != batchSize) {
            return MigrationBatchResult.Pending(parts.size, batchSize)
        }

        val entries = (0 until batchSize).flatMap { parts.getValue(it).entries }
        val issues = (0 until batchSize).flatMap { parts.getValue(it).issues }
        parts.clear()
        batchId = null
        batchSize = 0
        return MigrationBatchResult.Complete(entries, issues)
    }

    fun clear() {
        parts.values.forEach { it.wipe() }
        parts.clear()
        batchId = null
        batchSize = 0
    }

    private fun MigrationBatch.wipe() {
        entries.forEach { it.secret.fill(0) }
    }
}
