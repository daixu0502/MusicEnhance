package vip.chener.musicenhance.player.media

/** A vanished or blocked UI must never accumulate an unbounded queue of Binder transactions. */
internal class PlayerBridgeClients {
    private class Client(var expiresAtMs: Long) {
        var lastSnapshotRevision = 0L
        var snapshotInFlight: Long? = null
        var spectrumInFlight: Long? = null
    }

    private val clients = linkedMapOf<String, Client>()

    @Synchronized fun observe(id: String, active: Boolean, nowMs: Long) {
        expire(nowMs)
        if (!active) {
            clients.remove(id)
        } else if (id in clients) {
            clients.getValue(id).expiresAtMs = nowMs + LEASE_MS
        } else if (clients.size < MAX_CLIENTS) {
            clients[id] = Client(nowMs + LEASE_MS)
        }
    }

    @Synchronized fun hasClients(nowMs: Long): Boolean {
        expire(nowMs)
        return clients.isNotEmpty()
    }

    @Synchronized fun needsSnapshot(revision: Long, nowMs: Long): Boolean {
        expire(nowMs)
        return clients.values.any { it.snapshotInFlight == null && it.lastSnapshotRevision < revision }
    }

    @Synchronized fun claimSnapshot(revision: Long, nowMs: Long): List<String> {
        expire(nowMs)
        return clients.filterValues {
            it.snapshotInFlight == null && it.lastSnapshotRevision < revision
        }.onEach { (_, client) ->
            client.snapshotInFlight = revision
            client.lastSnapshotRevision = revision
        }.keys.toList()
    }

    @Synchronized fun claimSpectrum(sequence: Long, nowMs: Long): List<String> {
        expire(nowMs)
        return clients.filterValues { it.spectrumInFlight == null }.onEach { (_, client) ->
            client.spectrumInFlight = sequence
        }.keys.toList()
    }

    @Synchronized fun acknowledge(id: String, sequence: Long, spectrum: Boolean) {
        val client = clients[id] ?: return
        if (spectrum) {
            if (client.spectrumInFlight == sequence) client.spectrumInFlight = null
        } else if (client.snapshotInFlight == sequence) client.snapshotInFlight = null
    }

    private fun expire(nowMs: Long) {
        clients.values.removeAll { it.expiresAtMs <= nowMs }
    }

    companion object {
        const val LEASE_MS = 15_000L
        private const val MAX_CLIENTS = 4
    }
}
