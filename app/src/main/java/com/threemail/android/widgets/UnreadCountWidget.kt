package com.threemail.android.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.threemail.android.MainActivity
import com.threemail.android.R
import com.threemail.android.data.local.ThreeMailDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Small home-screen widget showing the current unread inbox count. */
class UnreadCountWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_REFRESH) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, UnreadCountWidget::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isNotEmpty()) {
                    val count = ThreeMailDatabase.getInstance(context).messageDao()
                        .observeTotalUnreadAcrossInboxes()
                        .first()
                    render(context, manager, ids, count)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val count = ThreeMailDatabase.getInstance(context).messageDao()
                    .observeTotalUnreadAcrossInboxes()
                    .first()
                updateWidgets(context, manager, ids, count)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.threemail.android.widgets.action.REFRESH"

        /** Request an immediate refresh through the receiver's goAsync lifecycle. */
        fun requestUpdate(context: Context) {
            val intent = Intent(context, UnreadCountWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }

        private fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            knownCount: Int?
        ) {
            if (knownCount != null) {
                render(context, manager, ids, knownCount)
                return
            }
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val count = ThreeMailDatabase.getInstance(context).messageDao()
                    .observeTotalUnreadAcrossInboxes()
                    .first()
                render(context, manager, ids, count)
            }
        }

        private fun render(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            count: Int
        ) {
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_unread_count).apply {
                    setTextViewText(
                        R.id.widget_unread_count,
                        context.resources.getQuantityString(
                            R.plurals.widget_unread_count,
                            count,
                            count
                        )
                    )
                    setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getActivity(
                            context,
                            id,
                            Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}
