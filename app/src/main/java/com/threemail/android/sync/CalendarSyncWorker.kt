package com.threemail.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threemail.android.data.remote.calendar.CalendarApiClient
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.CalendarRepository
import com.threemail.android.domain.model.AccountType
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * Manually constructed by [ThreeMailWorkerFactory]. See that class
 * doc for the rationale (androidx.hilt 1.4.0 silently skips
 * generating @HiltWorker AssistedFactory bindings under KSP2).
 */
class CalendarSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val calendarRepository: CalendarRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val accounts = accountRepository.getAccounts().first()
                .filter { it.isActive && it.calendarSyncEnabled }
                .filter { it.accountType == AccountType.GMAIL }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val windowStart = today.minusDays(DAYS_BACK).atStartOfDay(zone).toInstant().toEpochMilli()
            val windowEnd = today.plusDays(DAYS_FORWARD).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            accounts.forEach { account ->
                runCatching {
                    calendarRepository.syncRange(account, CalendarApiClient.PRIMARY_CALENDAR, windowStart, windowEnd)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        /** Keep ~6 months behind the cursor so navigation can show last-month overflow cheaply. */
        private const val DAYS_BACK = 30L
        /** ~12 weeks of forward visibility to cover a typical quarter without over-fetching. */
        private const val DAYS_FORWARD = 84L
    }
}
