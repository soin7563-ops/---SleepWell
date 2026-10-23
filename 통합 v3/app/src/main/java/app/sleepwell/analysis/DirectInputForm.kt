package app.sleepwell.analysis

import app.sleepwell.health.DisplaySettings
import app.sleepwell.health.DirectInput
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * 4단계 직접 입력: 화면의 글자 입력 → 채윤 파이프라인의 DirectInput 으로 바꾸는 곳. (소빈 · 2026-09-25)
 *
 * 근거 (1순위 md #5)
 *  - "필요한 경우 최근 취침·기상 시각이나 예상 취침·기상 시각 등 기본 정보를 직접 입력"
 *  - cf. "사용자가 입력한 개인 정보(컨디션 상태, 전날 취침 시간 및 기상 시간, 갤럭시 워치 건강정보)를 받아 분석"
 *  - 가능하다면 "내일 몇 시에 일어나야 하는지 / 오늘 몇 시쯤 잘 예정인지" 입력 → 취침·수면시간 계산
 *
 * 지금 모델에 실제로 반영되는 것은 "최근 취침~기상"(수면 구간) 뿐이다.
 *  - 목표 기상 / 예정 취침: DirectInput 에 담아 넘기지만, 권장 시각 계산(서윤 #7)이 없어 아직 쓰이지 않는다.
 *  - 컨디션: DirectInput 에 자리가 없다(채윤 합의안에 없음). 화면에서 기록만 하고 "모델 반영 전"으로 표시한다.
 *
 * 시각 형식은 "yyyy-MM-dd HH:mm". (2026-10) 화면에 선택된 표시 시간대(한국 시간/UTC)로 읽는다 —
 * 사용자는 화면에 보이는 시각으로 입력하므로. 기본은 한국 시간이라 기존 동작과 같다.
 */
data class DirectInputForm(
    val bedText: String = "",
    val wakeText: String = "",
    val plannedBedText: String = "",
    val targetWakeText: String = "",
    /** 컨디션 1(나쁨) ~ 5(좋음). 선택 안 하면 null */
    val condition: Int? = null
)

data class ParsedDirectInput(
    /** 입력이 하나도 없으면 null */
    val input: DirectInput?,
    val errors: List<String>
) {
    val ok: Boolean get() = errors.isEmpty()
}

object DirectInputParser {

    const val PATTERN = "yyyy-MM-dd HH:mm"
    private const val MAX_SLEEP_MS = 16 * 3_600_000L
    /** 파이프라인 계산 범위(기준 시각 이전 7일). 채윤-소빈 합의값 */
    const val WINDOW_MS = 7 * 24 * 3_600_000L

    /**
     * 이 파일로 직접 입력을 반영할 수 있는 마지막 기상 시각(이 시각 **전**까지).
     * 기상 시각이 기준이 되면 그 이전 7일을 계산하는데, 그 안에 업로드 기록이 하나도 없으면
     * 기록 없는 날을 전부 '깨어 있음'으로 계산하게 되어(S 가 1.0 에 붙음) 파이프라인이 거부한다.
     */
    fun latestWakeAllowed(lastRecordEndMs: Long): Long = lastRecordEndMs + WINDOW_MS

    /**
     * (2026-09-25 추가) 이 시각 **이후**에 깬 수면만 반영된다.
     * 기상이 마지막 기록보다 앞이면 기준 시각은 마지막 기록 그대로이고, 계산 범위는 그 이전 7일뿐이라
     * 더 오래된 입력은 파이프라인에서 조용히 무시된다(결과는 그대로인데 화면엔 "반영됨"으로 나오던 문제).
     */
    fun earliestWakeAllowed(lastRecordEndMs: Long): Long = lastRecordEndMs - WINDOW_MS

    private fun format() = SimpleDateFormat(PATTERN, Locale.US).apply {
        timeZone = DisplaySettings.zone.tz
        isLenient = false          // 2026-09-31 같은 날짜를 조용히 10/1 로 넘기지 않게
    }

    fun toText(ms: Long): String = format().format(java.util.Date(ms))

    fun parse(form: DirectInputForm, lastRecordEndMs: Long? = null): ParsedDirectInput {
        val errors = mutableListOf<String>()

        fun read(label: String, text: String): Long? {
            val t = text.trim()
            if (t.isEmpty()) return null
            return try {
                val pos = java.text.ParsePosition(0)
                val d = format().parse(t, pos)
                if (d == null || pos.index != t.length) { errors += "$label: \"$t\" 형식이 맞지 않아요 (예: 2026-09-15 23:30)"; null }
                else d.time
            } catch (e: Exception) {
                errors += "$label: \"$t\" 형식이 맞지 않아요 (예: 2026-09-15 23:30)"; null
            }
        }

        val bed = read("최근 취침", form.bedText)
        val wake = read("최근 기상", form.wakeText)
        val plannedBed = read("예정 취침", form.plannedBedText)
        val targetWake = read("목표 기상", form.targetWakeText)

        if ((bed == null) != (wake == null) && errors.isEmpty()) {
            errors += "최근 취침과 기상 시각은 둘 다 입력해야 수면으로 반영돼요."
        }
        if (bed != null && wake != null) {
            if (wake <= bed) errors += "최근 기상 시각이 취침 시각보다 늦어야 해요."
            else if (wake - bed > MAX_SLEEP_MS) errors += "수면이 16시간을 넘어요. 날짜를 확인해 주세요."
        }
        if (bed != null && wake != null && wake > bed && lastRecordEndMs != null &&
            wake >= latestWakeAllowed(lastRecordEndMs)) {
            errors += "업로드한 기록의 마지막이 ${toText(lastRecordEndMs)} 이라서, 기상 시각은 " +
                    "${toText(latestWakeAllowed(lastRecordEndMs))} 이전이어야 반영돼요. " +
                    "(그 사이 기록이 없으면 전부 '깨어 있음'으로 계산돼 결과가 틀어져요)"
        }
        if (bed != null && wake != null && wake > bed && lastRecordEndMs != null &&
            wake <= earliestWakeAllowed(lastRecordEndMs)) {
            errors += "계산 범위는 업로드 기록의 마지막(${toText(lastRecordEndMs)}) 이전 7일이라서, 기상 시각은 " +
                    "${toText(earliestWakeAllowed(lastRecordEndMs))} 이후여야 반영돼요."
        }
        if (form.condition != null && form.condition !in 1..5) errors += "컨디션은 1~5 중에서 골라 주세요."

        val any = listOf(bed, wake, plannedBed, targetWake).any { it != null }
        val input = if (!any) null else DirectInput(
            sleepStartMs = bed,
            sleepEndMs = wake,
            targetWakeMs = targetWake,
            plannedBedMs = plannedBed
        )
        return ParsedDirectInput(input, errors)
    }
}
