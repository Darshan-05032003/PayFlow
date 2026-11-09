package com.darshan.payflow.domain.model

/**
 * Payees that always end the same way in the sandbox, so every outcome can be walked in the app. They only mean something to the
 * sandbox provider: real UPI apps never see them unless someone types them by hand.
 */
object SandboxDemoPayees {
    const val SUCCESS = "alice@okbank"
    const val SLOW = "slowbank@okbank"
    const val STUCK = "stuck@okbank"
    const val DECLINED = "declined@okbank"
    const val REFUNDED = "refunded@okbank"
    const val MISMATCH = "mismatch@okbank"

    /** How many status checks a slow bank answers "pending" to before it settles. */
    const val SLOW_PENDING_POLLS = 5
}
