package app.knotwork.android.data.engine.retry

/**
 * The `Retry-After` header of the latest error answer one client received.
 *
 * The transport records it ([app.knotwork.android.data.engine.KoogTransportFactory]) because
 * Koog's exceptions carry no headers; [RetryingCloudLlmClient] reads it after a failed attempt.
 * One slot belongs to one client, and the factories build one client per operation (the
 * single-operation contract of [RetryingCloudLlmClient]), so its attempts run one after another
 * and a plain volatile field is enough.
 */
class RetryAfterSlot {

    @Volatile
    private var header: String? = null

    /**
     * Records the header of an error answer, replacing what an earlier answer left.
     *
     * @param value The header, or `null` when the answer had none.
     */
    fun record(value: String?) {
        header = value
    }

    /** Forgets the recorded header, before an attempt starts. */
    fun clear() {
        header = null
    }

    /**
     * The recorded header, forgotten as it is read so a later attempt cannot see it.
     *
     * @return The header of the latest error answer since [clear], or `null`.
     */
    fun take(): String? = header.also { header = null }
}
