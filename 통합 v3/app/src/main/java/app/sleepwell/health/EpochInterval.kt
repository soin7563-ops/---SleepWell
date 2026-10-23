package app.sleepwell.health

/**
 * 모델 입력 epoch 간격 (두 가지 값을 모두 지원).
 *
 * 왜 enum인가:
 *   - 30초는 Sleep-EDF / 평가코드 공통 격자(기준). 1분은 앱 화면·리포팅용 경량 격자.
 *   - 두 값을 코드 한 곳에 정의해두면, 나중에 "분리 10분 epoch" 같은 게 필요해질 때
 *     여기 한 줄 추가로 끝나고, 하드코딩된 30_000L 을 다시 찾을 필요가 없다.
 *   - 하드코딩(`30_000L`)이 파이프라인 두 군데에 흩어져 있었다. 이번에 `EpochInterval`
 *     로 모아서, 1분으로 바꿀 때 빠뜨리는 일이 없게 한다.
 *
 * 주의 (중요):
 *   이 값은 **Two-Process 모델 입력 격자**에만 해당한다.
 *   앱 export 격자(`HealthModels.EPOCH_MS`, `EpochAggregator`)는 소빈 쪽 파일이고
 *   Sleep-EDF 30초 표준에 묶여 있다. 그쪽은 이 enum과 별개로 30초를 유지한다.
 *   두 값을 한꺼번에 바꾸면 평가코드와 join이 어긋나 점수가 조용히 틀린다.
 */
enum class EpochInterval(
    /** epoch 길이 (밀리초) */
    val intervalMs: Long,
    /** epoch 길이 (초) */
    val seconds: Int,
    /** 화면·리포트 표시용 라벨 */
    val label: String
) {
    /** 기존 버전. Sleep-EDF / 평가코드 공통 격자. */
    SEC30(30_000L, 30, "30초"),

    /** 이번 주 추가한 버전. 계산량은 절반, 단기 경향·화면 표시에 충분. */
    MIN1(60_000L, 60, "1분");

    companion object {
        /** 파이프라인 기본값. 호출 측에서 명시하지 않으면 이 값이 쓰인다. */
        val DEFAULT: EpochInterval = SEC30

        /** epoch 길이(초)로 조회. 없으면 null. */
        fun fromSeconds(seconds: Int): EpochInterval? =
            entries.firstOrNull { it.seconds == seconds }
    }
}

/**
 * epoch 경계 정렬.
 *
 * 왜 필요한가:
 *   타임라인 시작 시각이 epoch 경계에 떨어지지 않으면 모든 epoch가 ":30" 처럼 어긋난
 *   시각에 놓인다. TwoProcessModel의 C_sleep는 epoch 시각의 시계 시각(시+분/60+초/3600)을
 *   그대로 쓰므로, 어긋나면 C가 실제보다 최대 (interval/2) 만큼 밀린다.
 *   30초에서는 최대 15초, 1분에서는 최대 30초 차이 → 하루 리듬 위상 계산에 그대로 남는다.
 *
 * 기준선은 Unix epoch(0ms). 30초·60초 모두 0에서 나누어떨어지므로 KST 자정/정오와도 일치한다.
 *   KST는 UTC+9:00으로 정수 시간대라, 60_000의 배수인 시각의 초·분은 항상 0이다.
 */
object EpochGrid {

    /** 시각을 epoch 경계로 내림(floor). */
    fun floorTo(tsMs: Long, interval: EpochInterval): Long {
        val m = interval.intervalMs
        return tsMs - Math.floorMod(tsMs, m)
    }

    /** 시각을 epoch 경계로 올림(ceil). 이미 경계면 그대로. */
    fun ceilTo(tsMs: Long, interval: EpochInterval): Long {
        val m = interval.intervalMs
        val rem = Math.floorMod(tsMs, m)
        return if (rem == 0L) tsMs else tsMs + (m - rem)
    }

    /** 시각이 epoch 경계에 떨어지는지. */
    fun isAligned(tsMs: Long, interval: EpochInterval): Boolean =
        Math.floorMod(tsMs, interval.intervalMs) == 0L
}