package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.ai.mcp.McpStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure presentation of observed values; this object never reads settings or starts services. */
internal object SettingsDashboardStatusPresentation {
    fun ssh(hostCount: Int): SettingsDashboardStatus.Ready =
        if (hostCount == 0) SettingsDashboardStatus.Ready() else ready("$hostCount хостов")

    fun workflows(activeCount: Int): SettingsDashboardStatus.Ready =
        if (activeCount == 0) SettingsDashboardStatus.Ready() else ready("$activeCount активных")

    fun cron(scheduledCount: Int): SettingsDashboardStatus.Ready =
        if (scheduledCount == 0) SettingsDashboardStatus.Ready() else ready("$scheduledCount в расписании")

    fun mcp(statuses: Collection<McpStatus>?): SettingsDashboardStatus.Ready {
        if (statuses == null) return SettingsDashboardStatus.Ready()
        val connected = statuses.count { it == McpStatus.Connected }
        val hasError = statuses.any { it is McpStatus.Error }
        val needsAuthorization = statuses.any { it == McpStatus.NeedsAuthorization }
        val tone = when {
            hasError -> SettingsDashboardStatusTone.Error
            needsAuthorization -> SettingsDashboardStatusTone.Warning
            connected > 0 -> SettingsDashboardStatusTone.Success
            else -> SettingsDashboardStatusTone.Neutral
        }
        if (connected == 0) return when {
            hasError -> ready("Ошибка подключения", tone)
            needsAuthorization -> ready("Нужен вход", tone)
            else -> SettingsDashboardStatus.Ready()
        }
        val suffix = when {
            hasError -> " · ошибка"
            needsAuthorization -> " · нужен вход"
            else -> ""
        }
        return ready("$connected подключено$suffix", tone)
    }

    fun storage(fileCount: Int, sizeBytes: Long): SettingsDashboardStatus.Ready = when {
        fileCount == 0 && sizeBytes == 0L -> SettingsDashboardStatus.Ready()
        fileCount == 0 -> ready(formatSize(sizeBytes))
        sizeBytes == 0L -> ready("Файлов: $fileCount")
        else -> ready("Файлов: $fileCount · ${formatSize(sizeBytes)}")
    }

    fun backup(lastBackupTime: Long, timeZone: TimeZone): SettingsDashboardStatus.Ready {
        if (lastBackupTime <= 0L) return SettingsDashboardStatus.Ready()
        val formatter = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).apply { this.timeZone = timeZone }
        return ready(formatter.format(Date(lastBackupTime)))
    }

    /** Binary thresholds, a Russian decimal comma, and at most one fractional digit. */
    fun formatSize(bytes: Long): String {
        val (divisor, unit) = when {
            bytes >= GIB -> GIB to "ГБ"
            bytes >= MIB -> MIB to "МБ"
            bytes >= KIB -> KIB to "КБ"
            else -> 1L to "Б"
        }
        val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
            decimalSeparator = ','
            groupingSeparator = ' '
        }
        val formatter = DecimalFormat("#,##0.#", symbols).apply { roundingMode = RoundingMode.HALF_UP }
        val value = BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(divisor))
        return "${formatter.format(value)} $unit"
    }

    private fun ready(text: String, tone: SettingsDashboardStatusTone = SettingsDashboardStatusTone.Neutral) =
        SettingsDashboardStatus.Ready(SettingsDashboardStatusChip(text, tone))

    private const val KIB = 1024L
    private const val MIB = KIB * 1024L
    private const val GIB = MIB * 1024L
}
