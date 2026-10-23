package app.sleepwell.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.sleepwell.health.DisplaySettings
import app.sleepwell.health.DisplayZone

/**
 * 화면 표시 시간대 (UTC / 한국 시간) 상태. (소빈 · 2026-10)
 *
 * Compose 상태로 들고 있어서, 화면에서 `UiZone.current` 를 읽은 곳은 시간대를 바꾸면 자동으로 다시 그려진다.
 * 바꿀 때 DisplaySettings.zone 도 함께 바꿔서, 화면 밖 코드(AnalysisPresenter·입력 해석)도 같은 값을 쓰게 한다.
 * 계산·export 는 이 값을 쓰지 않는다 (HealthModels.kt 의 DisplayZone 설명 참고).
 */
object UiZone {
    private var state by mutableStateOf(DisplaySettings.zone)

    var current: DisplayZone
        get() = state
        set(value) {
            DisplaySettings.zone = value
            state = value
        }
}
