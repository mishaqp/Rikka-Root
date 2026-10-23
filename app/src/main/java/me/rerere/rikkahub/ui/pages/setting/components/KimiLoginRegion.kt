package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.rikkahub.data.kimi.KimiOAuthStatus
import me.rerere.rikkahub.data.kimi.KimiRegion

internal fun kimiLoginRegion(selected: KimiRegion?, status: KimiOAuthStatus, accountRegion: KimiRegion?): KimiRegion =
    selected ?: (status as? KimiOAuthStatus.Waiting)?.region ?: accountRegion ?: KimiRegion.CN
