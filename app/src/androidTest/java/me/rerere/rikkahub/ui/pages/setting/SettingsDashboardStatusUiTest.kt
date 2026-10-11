package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test

class SettingsDashboardStatusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aReadyCellRendersWhileAnotherCellIsStillLoading() {
        val root = CompletableDeferred<SettingsDashboardStatus>()
        val storage = CompletableDeferred<SettingsDashboardStatus>()
        val sources = mapOf(
            SettingsDashboardStatusKey.Root to flow { emit(root.await()) },
            SettingsDashboardStatusKey.Storage to flow { emit(storage.await()) },
        )
        compose.setContent {
            val statuses = rememberSettingsDashboardStatuses(sources)
            Column {
                for (key in listOf(SettingsDashboardStatusKey.Root, SettingsDashboardStatusKey.Storage)) {
                    val text = when (val status = statuses.getValue(key)) {
                        SettingsDashboardStatus.Loading -> "Загрузка"
                        is SettingsDashboardStatus.Ready -> status.chip?.text.orEmpty()
                        SettingsDashboardStatus.Error -> "Ошибка"
                    }
                    Text(text, Modifier.testTag(key.name))
                }
            }
        }
        compose.onNodeWithTag("Root").assertTextEquals("Загрузка")
        compose.onNodeWithTag("Storage").assertTextEquals("Загрузка")

        compose.runOnIdle {
            root.complete(SettingsDashboardStatus.Ready(SettingsDashboardStatusChip("Активен")))
        }
        compose.onNodeWithTag("Root").assertTextEquals("Активен")
        compose.onNodeWithTag("Storage").assertTextEquals("Загрузка")

        compose.runOnIdle {
            storage.complete(SettingsDashboardStatus.Ready(SettingsDashboardStatusChip("Файлов: 2")))
        }
        compose.onNodeWithTag("Storage").assertTextEquals("Файлов: 2")
    }
}
