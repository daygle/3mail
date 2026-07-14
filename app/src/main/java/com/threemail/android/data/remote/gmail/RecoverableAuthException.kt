package com.threemail.android.data.remote.gmail

import android.content.Intent

/**
 * Thrown when an OAuth operation requires the user to grant additional consent.
 * The [intent] should be launched with startActivityForResult so the user can
 * approve access; after the activity returns, the original operation can be retried.
 */
class RecoverableAuthException(
    val intent: Intent,
    message: String = "Additional Google consent is required"
) : Exception(message)
