package com.threemail.android.push

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushControllerTest {

    private val appPackage = "com.threemail.android"

    @Test
    fun `refresh intent has the right action and component`() {
        val intent = PushController.buildIntent(appPackage, ImapIdleService.ACTION_REFRESH)

        assertEquals(ImapIdleService.ACTION_REFRESH, intent.action)
        assertEquals(
            ComponentName(appPackage, ImapIdleService::class.java.name),
            intent.component
        )
        assertFalse(intent.hasExtra(ImapIdleService.EXTRA_ACCOUNT_ID))
    }

    @Test
    fun `start intent exposes a stable action constant`() {
        val intent = PushController.buildIntent(appPackage, ImapIdleService.ACTION_START_FOR_ACCOUNT)
        assertEquals(
            "com.threemail.android.push.START_FOR_ACCOUNT",
            intent.action
        )
    }

    @Test
    fun `stop intent exposes a stable action constant`() {
        val intent = PushController.buildIntent(appPackage, ImapIdleService.ACTION_STOP_FOR_ACCOUNT)
        assertEquals(
            "com.threemail.android.push.STOP_FOR_ACCOUNT",
            intent.action
        )
    }

    @Test
    fun `extras can be set on the produced intent`() {
        val intent = PushController.buildIntent(appPackage, ImapIdleService.ACTION_START_FOR_ACCOUNT)
            .putExtra(ImapIdleService.EXTRA_ACCOUNT_ID, 42L)

        assertTrue(intent.hasExtra(ImapIdleService.EXTRA_ACCOUNT_ID))
        assertEquals(42L, intent.getLongExtra(ImapIdleService.EXTRA_ACCOUNT_ID, -1L))
    }
}
