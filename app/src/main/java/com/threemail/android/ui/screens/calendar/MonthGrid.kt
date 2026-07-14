package com.threemail.android.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.threemail.android.domain.model.CalendarEvent
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Calendar indicator palette — six sturdy, accessible colors keyed to calendarId. */
val CalendarIndicatorColors: List<Color> = listOf(
    Color(0xFF5B54E6), // indigo
    Color(0xFF0EA5E9), // sky
    Color(0xFF14B8A6), // teal
    Color(0xFFF97316), // orange
    Color(0xFFEC4899), // pink
    Color(0xFF8B5CF6)  // violet
)

/** Deterministic color for an event's calendar. Stable across recompositions. */
fun getEventColor(calendarId: String): Color {
    val index = (calendarId.hashCode() and 0x7FFFFFFF) % CalendarIndicatorColors.size
    return CalendarIndicatorColors[index]
}

/**
 * Seven-column × six-row month grid. The visible month is rendered with the leading
 * days of the previous month padded so the first row always aligns to Sunday. Each cell
 * shows the day number plus up to [VISIBLE_EVENTS] indicator pills; overflow is
 * collapsed into a single "+N more" label.
 */
@Composable
fun MonthGrid(
    visibleMonth: YearMonth,
    eventsByDay: Map<LocalDate, List<CalendarEvent>>,
    selectedDay: LocalDate,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") zone: ZoneId = ZoneId.systemDefault()
) {
    val daysOfWeek = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val firstOfMonth = visibleMonth.atDay(1)
    // Sunday = 7, others map to value - 1; using % 7 keeps Sunday at offset 0.
    val startOffset = firstOfMonth.dayOfWeek.value % 7
    val startDate = firstOfMonth.minusDays(startOffset.toLong())

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            daysOfWeek.forEach { dayName ->
                Text(
                    text = dayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }

        for (row in 0 until 6) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val date = startDate.plusDays((row * 7 + col).toLong())
                    DayCell(
                        date = date,
                        isCurrentMonth = YearMonth.from(date) == visibleMonth,
                        isToday = date == today,
                        isSelected = date == selectedDay,
                        events = eventsByDay[date].orEmpty(),
                        onClick = { onDayClick(date) },
                        onEventClick = onEventClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private const val VISIBLE_EVENTS = 2

@Composable
private fun DayCell(
    date: LocalDate,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    events: List<CalendarEvent>,
    onClick: () -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        // Day number badge.
        val bgColor = when {
            isToday -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        }
        val textColor = when {
            isToday -> MaterialTheme.colorScheme.onPrimaryContainer
            !isCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.onSurface
        }
        val badgeStyle = if (isToday) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodyMedium

        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(bgColor)
                // Selection ring sits on top of the today badge when both apply;
                // today wins for fill because it's the stronger semantic.
                .then(
                    if (isSelected) Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = badgeStyle,
                color = textColor
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        events.take(VISIBLE_EVENTS).forEach { event ->
            val accent = getEventColor(event.calendarId)
            if (event.allDay) {
                Surface(
                    color = accent,
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp)
                        .clickable { onEventClick(event) }
                ) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                        .clickable { onEventClick(event) }
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(14.dp)
                            .background(accent)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 4.dp, top = 2.dp, bottom = 2.dp)
                    )
                }
            }
        }

        if (events.size > VISIBLE_EVENTS) {
            Text(
                text = "+${events.size - VISIBLE_EVENTS} more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
