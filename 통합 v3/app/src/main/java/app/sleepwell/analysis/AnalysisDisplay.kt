package app.sleepwell.analysis

import app.sleepwell.health.AnalysisResult
import app.sleepwell.health.DisplaySettings
import app.sleepwell.health.DirectInput
import app.sleepwell.health.PredictedPoint
import app.sleepwell.health.PropensityPoint
import app.sleepwell.health.SleepRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * 분석 결과(AnalysisResult) → 화면에 그릴 값으로 정리하는 곳. (소빈 · 2026-09-25)
 *
 * 화면 코드(AnalysisScreen)와 분리한 이유
 *  - 계산·해석 규칙을 Android 없이 단위 테스트로 확인하기 위해서.
 *  - 채윤 쪽 AnalysisResult 가 바뀌어도 이 파일만 맞추면 화면은 그대로 둘 수 있게.
 *
 * ★ (2026-09-26) "높음/보통/낮음" 임의 경계는 없앴다. 값은 실제 숫자와 본인 기록(최근 7일)에 비교한 문장으로 설명한다.
 *   모델(서윤 #7)과 파라미터가 확정되지 않았으므로, 화면에 "임시 모델로 계산"이라고 함께 표시한다.
 *   값의 방향은 교정 보고서 기준: C_sleep 은 수면 중간 시각에 +최대, propensity = S + C_sleep (클수록 잠들기 쉬움).
 *
 * ★ (2026-10) "○○ 밤" 단위 조회 + 표시 시간대
 *   - 화면은 사용자가 고른 **밤 하나**(그날 12:00 ~ 다음 날 12:00, 표시 시간대 기준)를 보여준다.
 *     export 의 night 규칙(정오~정오)과 같은 경계라, 자정을 넘는 잠이 잘리지 않는다.
 *   - 모델 계산은 그대로(전체 기간 연속). 여기서는 그 결과에서 밤 하나만큼 잘라 보여줄 뿐이다.
 *   - "지금 상태"·"앞으로 예측"·개인 경향은 **가장 최근 밤**(마지막 기록이 들어 있는 밤)에서만 의미가 있어 그때만 채운다.
 *   - 고를 수 있는 밤 = 데이터(수면 기록) 전체 범위의 밤. (2026-10 변경: 이전엔 계산된 최근 7일 안만)
 *     지난 밤을 고르면 화면(AnalysisScreen)이 그 밤의 끝을 기준 시각으로 다시 계산한다 → 그 밤 앞 7일이 계산 구간.
 *     기록이 시작된 첫 밤은 그 앞 기록이 없어 수면 압력이 덜 정확하다(isPartial).
 *   - 시각·날짜 글자는 DisplaySettings.zone(표시 시간대)을 따른다.
 */

/** 그래프 한 점 */
data class ChartPoint(val timeMs: Long, val s: Double, val c: Double, val propensity: Double)

/**
 * 화면에서 고르는 "밤" 하나 = startMs(그날 12:00) ~ endMs(다음 날 12:00), 표시 시간대 기준.
 * @param isLatest 마지막 기록(기준 시각)이 들어 있는 밤 → "지금 상태"·예측을 보여준다
 * @param isPartial 데이터(수면 기록)가 시작된 직후의 밤 → 그 앞 기록이 없어 수면 압력이 덜 정확하다
 */
data class NightInfo(
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val rangeText: String,
    val isLatest: Boolean,
    val isPartial: Boolean
)

/** 그래프 배경에 칠할 수면 구간 */
data class SleepBand(val startMs: Long, val endMs: Long)

/** 값이 범위 안 어디쯤인지 보여주는 막대 */
data class Gauge(val min: Double, val max: Double, val value: Double, val leftLabel: String, val rightLabel: String)

enum class ReadingKind { S, C, P }

/**
 * 한 줄 해석: 제목 · 값 · 쉬운 설명.
 * (2026-09-26) 높음/보통/낮음 같은 임의 경계 대신, 실제 값·본인 기록과 비교한 문장으로 설명한다.
 */
data class Reading(
    val title: String,
    val value: String,
    val meaning: String,
    val kind: ReadingKind = ReadingKind.S,
    val gauge: Gauge? = null,
    val details: List<String> = emptyList(),
    val note: String? = null
)

data class AnalysisDisplay(
    val modelLabel: String,
    val isTemporaryModel: Boolean,
    /** 기준 시각 = 마지막 계산 시점 (과거 그래프와 미래 그래프의 경계) */
    val referenceMs: Long,
    val readings: List<Reading>,
    /** 과거 구간 (다운샘플) */
    val history: List<ChartPoint>,
    /** 미래 구간 (다운샘플). 비어 있으면 "준비 중" */
    val future: List<ChartPoint>,
    val futureIsTemporary: Boolean,
    val sleepBands: List<SleepBand>,
    /** 앞으로 24시간 중 propensity 가 가장 큰 시각 (임시 예측). 미래 값이 없으면 null */
    val easiestSleepMs: Long?,
    val recentSleeps: List<SleepRecord>,
    val recommendationText: String,
    /** 직접 입력한 수면 구간 (그래프에 다른 색으로 칠함). 없으면 null */
    val manualSleep: SleepBand? = null,
    /** 직접 입력 요약 문구 (화면에 그대로 표시) */
    val inputNotes: List<String> = emptyList(),
    /** 업로드 기록의 마지막 기상 시각 (직접 입력 가능 범위 안내용) */
    val lastRecordEndMs: Long? = null,
    /** 그래프 아래 "개인 경향" 문장. 기록이 2번 미만이면 비어 있음 → 화면에서 숨김 */
    val patternLines: List<String> = emptyList(),
    /** 하루 넘게 수면 기록이 없는 구간 (그래프에 빗금). '깨어 있음'으로 계산돼 값이 높게 보이는 곳 */
    val gapBands: List<SleepBand> = emptyList(),
    /** 지금 보고 있는 밤 (그래프 범위 = night.startMs ~ night.endMs) */
    val night: NightInfo? = null,
    /** 고를 수 있는 밤 목록 (오래된 순) */
    val nights: List<NightInfo> = emptyList(),
    /** 이 밤에 잠든 수면 기록 (잠든 시각이 이 밤 범위 안) */
    val nightSleeps: List<SleepRecord> = emptyList(),
    /** 이 결과의 계산 구간(앞 7일)에 걸친 수면 기록 수. 적으면 '깨어 있음'으로 채워져 수면 압력이 높게 나온다 */
    val windowSleepCount: Int = 0
)

object AnalysisPresenter {

    /** (2026-10 이전) 그래프에 보여주던 과거 기간. 지금 화면은 "○○ 밤"(24시간) 단위라 쓰지 않는다 */
    const val DEFAULT_WINDOW_DAYS = 3
    /** 그래프 간격 (합의: 계산 1분(2026-10, 이전 30초), 전달·그래프 10~15분) */
    const val DEFAULT_STEP_MIN = 15

    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 60 * MIN_MS
    private const val DAY_MS = 24 * 60 * MIN_MS
    /** 이보다 짧은 수면은 경향 문장에서 뺀다 (낮잠이 "잠드는 시각" 평균을 흐리지 않게) */
    private const val MAIN_SLEEP_MIN = 180.0
    /** 이보다 길게 수면 기록이 없으면 "기록 없음"으로 본다 (하루 넘게 한 번도 안 잤을 가능성은 낮음) */
    private const val GAP_MS = DAY_MS

    /**
     * @param r 이 밤을 계산한 결과. 가장 최근 밤 = 업로드 직후 계산(마지막 기록 기준 앞 7일),
     *          지난 밤 = 그 밤의 끝(다음 날 정오)을 기준 시각으로 다시 계산한 결과(그 앞 7일). (2026-10)
     * @param nightStartMs 보고 싶은 밤(그 밤 안의 아무 시각이나 됨). null 이거나 범위 밖이면 가장 최근 밤.
     * @param latestRefMs 가장 최근 계산의 기준 시각(마지막 기록). 지난 밤 결과를 넘길 때 "가장 최근 밤"을 알기 위해 필요.
     * @param dataStartMs 데이터(수면 기록)의 첫 시각 → 고를 수 있는 밤의 시작.
     */
    fun build(
        r: AnalysisResult,
        nightStartMs: Long? = null,
        stepMin: Int = DEFAULT_STEP_MIN,
        direct: DirectInput? = null,
        condition: Int? = null,
        latestRefMs: Long? = null,
        dataStartMs: Long? = null
    ): AnalysisDisplay {
        val tz = zone()
        val refMs = r.scHistory.lastOrNull()?.timestampMs ?: r.calculationTimeMs
        val stepMs = stepMin * MIN_MS

        // ── 고를 수 있는 밤 목록과 지금 볼 밤 ──
        // (2026-10) 계산 구간(7일)이 아니라 데이터 전체 범위. 지난 밤은 화면이 그 밤 기준으로 다시 계산해서 넘긴다.
        val latestRef = latestRefMs ?: refMs
        val dataStart = dataStartMs
            ?: listOfNotNull(r.sleepRecords.minOfOrNull { it.startMs }, r.scHistory.firstOrNull()?.timestampMs).minOrNull()
            ?: refMs
        val nights = nightsBetween(dataStart, latestRef, tz)
        val latest = nights.last()
        val night = nightStartMs?.let { want -> nightStartOf(want, tz).let { s -> nights.firstOrNull { it.startMs == s } } } ?: latest
        val fromMs = night.startMs
        val toMs = night.endMs

        val history = downsample(
            r.scHistory.asSequence()
                .filter { it.timestampMs in fromMs..minOf(toMs, refMs) }
                .map { ChartPoint(it.timestampMs, it.S, it.C, it.propensity) },
            stepMs
        )
        val futureAll = downsample(
            (r.futureS ?: emptyList()).asSequence()
                .map { ChartPoint(it.timestampMs, it.predictedS, it.predictedC, it.predictedPropensity) },
            stepMs
        )
        // 그래프에는 이 밤 범위 안의 예측만. 지난 밤에는 예측이 없다.
        val future = if (night.isLatest) futureAll.filter { it.timeMs <= toMs } else emptyList()

        val bands = r.sleepRecords
            .filter { it.endMs > fromMs && it.startMs < toMs }
            .map { SleepBand(maxOf(it.startMs, fromMs), minOf(it.endMs, toMs)) }

        // "안 자고 버틴다면 가장 졸린 때"는 그래프 범위와 상관없이 앞으로 24시간 전체에서 찾는다 (가장 최근 밤에서만)
        val easiest = if (night.isLatest) futureAll.maxByOrNull { it.propensity }?.timeMs else null
        val manual = direct?.let { dd ->
            val s0 = dd.sleepStartMs; val e0 = dd.sleepEndMs
            if (s0 != null && e0 != null && e0 > s0) Sleep(s0, e0) else null
        }
        val sleeps = (r.sleepRecords.map { Sleep(it.startMs, it.endMs, it.totalSleepMin) } + listOfNotNull(manual))
            .filter { it.endMs <= refMs + MIN_MS }
            .sortedBy { it.startMs }
        // 모델 이름에 "임시"가 없어져도(채윤 최종본: "기준 코드 일치") 아직 임시 모델이다.
        // 파라미터가 확정되지 않았고 서윤 #7 모델로 교체 예정이므로 항상 표시한다 (합의안 5-1 #4).
        val temporary = true

        return AnalysisDisplay(
            modelLabel = r.modelVersion,
            isTemporaryModel = temporary,
            referenceMs = refMs,
            readings = if (night.isLatest) readings(r, refMs, sleeps) else emptyList(),
            history = history,
            future = future,
            futureIsTemporary = r.futureS != null,
            sleepBands = bands,
            easiestSleepMs = easiest,
            recentSleeps = if (night.isLatest) r.sleepRecords.sortedByDescending { it.startMs }.take(7) else emptyList(),
            recommendationText = recommendationText(r),
            manualSleep = direct?.let { d ->
                val s0 = d.sleepStartMs; val e0 = d.sleepEndMs
                if (s0 != null && e0 != null && e0 > fromMs && s0 < toMs) SleepBand(maxOf(s0, fromMs), minOf(e0, toMs)) else null
            },
            inputNotes = inputNotes(direct, condition),
            lastRecordEndMs = r.sleepRecords.maxOfOrNull { it.endMs },
            patternLines = if (night.isLatest) patternLines(r, refMs, sleeps) else emptyList(),
            gapBands = gapBands(sleeps, fromMs, minOf(toMs, refMs)),
            night = night,
            nights = nights,
            nightSleeps = r.sleepRecords.filter { it.startMs in fromMs until toMs }.sortedBy { it.startMs },
            windowSleepCount = r.scHistory.firstOrNull()?.timestampMs?.let { ws ->
                r.sleepRecords.count { it.endMs > ws && it.startMs < refMs }
            } ?: 0
        )
    }

    // ── 밤 경계 (표시 시간대의 정오~다음 날 정오) ──────────────────
    /** ms 가 속한 밤의 시작 = (ms − 12시간)의 날짜의 정오. export 의 night 규칙과 같은 방식 (시간대만 표시 시간대) */
    fun nightStartOf(ms: Long, tz: TimeZone = zone()): Long = Calendar.getInstance(tz).apply {
        timeInMillis = ms - 12 * HOUR_MS
        set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 이 밤의 끝 = 다음 날 정오 (지난 밤을 다시 계산할 때 기준 시각으로 쓴다) */
    fun nightEndOf(startMs: Long, tz: TimeZone = zone()): Long = nightStartOf(startMs + DAY_MS + HOUR_MS, tz)

    /** 데이터 범위 [dataStart, refMs] 에 걸치는 밤 목록 (오래된 순). 최소 1개. */
    fun nightsBetween(dataStart: Long, refMs: Long, tz: TimeZone = zone()): List<NightInfo> {
        val first = nightStartOf(dataStart, tz)
        val last = nightStartOf(refMs, tz)
        val out = mutableListOf<NightInfo>()
        var s = first
        while (s <= last && out.size < 5000) {      // 5000 밤 ≈ 13년 (안전 상한)
            val e = nightStartOf(s + DAY_MS + HOUR_MS, tz)     // 다음 날 정오 (서머타임이 있는 시간대에서도 맞게 다시 잡는다)
            out += NightInfo(
                startMs = s, endMs = e,
                label = fmt("M/d(E)", tz).format(Date(s)) + " 밤",
                rangeText = fmt("M/d HH:mm", tz).format(Date(s)) + " ~ " + fmt("M/d HH:mm", tz).format(Date(e)),
                isLatest = s == last,
                isPartial = s < dataStart + DAY_MS       // 기록이 시작된 첫 1~2밤: 그 앞 기록이 없어 수면 압력이 덜 정확
            )
            s = e
        }
        return out
    }

    /** 업로드 기록 + 직접 입력을 같은 모양으로 다루기 위한 내부 타입 */
    private data class Sleep(val startMs: Long, val endMs: Long, val sleptMin: Double? = null) {
        /** 누워 있던 시간 (시작~끝) */
        val minutes: Double get() = (endMs - startMs) / 60_000.0
        /** 실제로 잔 시간 (중간에 깬 시간 제외). 직접 입력은 단계 정보가 없어 시작~끝 */
        val slept: Double get() = sleptMin ?: minutes
    }

    /** 일정 간격마다 첫 점만 남긴다. 원본이 수백만 점이어도 그래프에는 수백 점만 넘긴다. */
    fun downsample(points: Sequence<ChartPoint>, stepMs: Long): List<ChartPoint> {
        val out = ArrayList<ChartPoint>()
        var nextMs = Long.MIN_VALUE
        for (p in points) {
            if (p.timeMs >= nextMs) {
                out += p
                nextMs = p.timeMs + stepMs
            }
        }
        return out
    }

    private fun readings(r: AnalysisResult, refMs: Long, sleeps: List<Sleep>): List<Reading> {
        val h = r.scHistory
        val future = r.futureS ?: emptyList()

        // ── 수면 압력: 마지막 수면 길이 + 지금부터 깨어 있으면 자정쯤 얼마나 쌓이는지 (실제 값으로만 설명)
        val last = sleeps.lastOrNull()
        val sLines = mutableListOf<String>()
        if (last != null) {
            val sAtOnset = nearest(h, last.startMs)?.S
            val sAtWake = nearest(h, last.endMs)?.S
            val wokeAgo = refMs - last.endMs
            val drop = if (sAtOnset != null && sAtWake != null && sAtOnset > sAtWake)
                "자는 동안 ${fmt(sAtOnset)} → ${fmt(sAtWake)}까지 빠졌어요." else null
            sLines += if (wokeAgo <= HOUR_MS) "${duration(last.slept)} 자고 막 일어났어요." + (drop?.let { " $it" } ?: "")
                      else "${wokeAgo / HOUR_MS}시간 전에 ${duration(last.slept)} 자고 일어났어요." + (drop?.let { " $it" } ?: "")
        }
        nextMidnight(future, refMs)?.let { (label, v) -> sLines += "이대로 깨어 있으면 ${label} ${fmt(v)}까지 쌓여요." }

        // ── 몸 시계: 지금 방향 + 하루 중 가장 잠을 원하는 때 / 가장 또렷한 때
        val c = r.currentC
        val cState = when {
            c > 0.05 -> "지금은 몸이 잠을 원하는 쪽이에요."
            c < -0.05 -> "지금은 몸이 또렷한 쪽이에요."
            else -> "지금은 잠을 원하는 쪽과 또렷한 쪽의 중간이에요."
        }
        val day = h.filter { it.timestampMs > refMs - DAY_MS }
        val cDetails = listOfNotNull(
            day.maxByOrNull { it.C }?.let { "몸이 가장 잠을 원하는 때: 매일 ${hhmm(it.timestampMs)} 전후" },
            day.minByOrNull { it.C }?.let { "몸이 가장 또렷한 때: 매일 ${hhmm(it.timestampMs)} 전후" }
        )

        // ── 잠들기 쉬운 정도: 최근 7일 중 어디쯤인지 (본인 기록과 비교 → 임의 경계 없음)
        val below = h.count { it.propensity < r.currentPropensity }
        val pct = if (h.isEmpty()) 50.0 else below * 100.0 / h.size
        val (rank, mood) = when {
            pct < 5 -> "최근 7일 중 가장 낮아요." to "지금은 잠들기 어려운 때예요."
            pct < 33 -> "최근 7일 중 낮은 편이에요." to "지금은 잠들기 어려운 때예요."
            pct < 67 -> "최근 7일 중 중간쯤이에요." to ""
            pct < 95 -> "최근 7일 중 높은 편이에요." to "지금은 잠들기 쉬운 때예요."
            else -> "최근 7일 중 가장 높아요." to "지금은 잠들기 쉬운 때예요."
        }
        val justWoke = last != null && refMs - last.endMs <= HOUR_MS
        val pMood = if (justWoke && pct < 33) "막 일어나서 $mood" else mood

        return listOf(
            Reading(
                "수면 압력", fmt(r.currentS), sLines.joinToString(" "), ReadingKind.S,
                gauge = Gauge(0.0, 1.0, r.currentS, "0 푹 잔 직후", "1 한계")
            ),
            Reading(
                "몸 시계 (일주기 리듬)", signed(c), cState, ReadingKind.C,
                gauge = Gauge(-0.15, 0.15, c, "−0.15 또렷한 쪽", "+0.15 잠을 원하는 쪽"),
                details = cDetails,
                note = "(최근 7일 동안 잔 시간을 바탕으로 계산)"
            ),
            Reading("잠들기 쉬운 정도", fmt(r.currentPropensity), listOf(rank, pMood).filter { it.isNotEmpty() }.joinToString(" "), ReadingKind.P)
        )
    }

    /**
     * 그래프 아래 "개인 경향" 문장 (선 모양이 아니라 이 사람의 패턴을 말한다).
     *  1) 최근 N번 기록의 잠든 시각 범위 · 일어난 시각
     *  2) 몸 시계가 가장 잠을 원하는 시각
     *  3) 지금부터 깨어 있으면 언제 지난번 잠들 때만큼 잠들기 쉬워지는지 (예측 값 기준)
     * 3시간 이상 잔 기록이 2번 미만이면 "경향"이라 하기 어려워 비운다.
     */
    private fun patternLines(r: AnalysisResult, refMs: Long, sleeps: List<Sleep>): List<String> {
        val recent = sleeps.filter { it.endMs > refMs - 7 * DAY_MS && it.minutes >= MAIN_SLEEP_MIN }
        if (recent.size < 2) return emptyList()
        val out = mutableListOf<String>()

        // 잠든 시각: 정오 기준으로 돌려서(밤 11시~새벽 1시처럼 자정을 넘어도) 범위를 잡는다
        val onsetH = recent.map { (hourOf(it.startMs) + 12) % 24 }
        val lo = (onsetH.min() + 12) % 24; val hi = (onsetH.max() + 12) % 24
        val onsetText = if (lo == hi) "${period(lo)} ${h12(lo)}시쯤"
            else if (period(lo) == period(hi)) "${period(lo)} ${h12(lo)}~${h12(hi)}시에"
            else "${period(lo)} ${h12(lo)}시~${period(hi)} ${h12(hi)}시에"
        val wakeText = clockWords(circularMeanMinutes(recent.map { minuteOfDay(it.endMs) }))
        out += "최근 ${recent.size}번의 기록에서 $onsetText 잠들어 ${wakeText}쯤 일어났어요."

        r.scHistory.filter { it.timestampMs > refMs - DAY_MS }.maxByOrNull { it.C }?.let {
            out += "몸 시계는 ${clockWords(minuteOfDay(it.timestampMs))}쯤(▲) 가장 잠을 원해요."
        }

        val last = sleeps.last()
        val onsetP = nearest(r.scHistory, last.startMs)?.propensity
        val reach = onsetP?.let { p -> r.futureS?.firstOrNull { it.predictedPropensity >= p } }
        if (reach != null) {
            val which = if (last.minutes >= MAIN_SLEEP_MIN) "지난밤" else "지난번"
            out += "지금부터 깨어 있으면 ${dayWord(refMs, reach.timestampMs)} ${clockWords(minuteOfDay(reach.timestampMs))}쯤, " +
                    "$which 잠들 때만큼 잠들기 쉬워져요."
        }
        return out
    }

    /** 표시 기간 안에서 하루 넘게 수면 기록이 없는 구간 */
    private fun gapBands(sleeps: List<Sleep>, fromMs: Long, refMs: Long): List<SleepBand> {
        val out = mutableListOf<SleepBand>()
        var cursor = fromMs
        for (s in sleeps) {
            if (s.endMs <= fromMs) continue
            if (s.startMs - cursor > GAP_MS) out += SleepBand(cursor, s.startMs)
            cursor = maxOf(cursor, s.endMs)
        }
        if (refMs - cursor > GAP_MS) out += SleepBand(cursor, refMs)
        return out
    }

    // ── 시각·문장 도우미 ─────────────────────────────────────
    private fun nearest(h: List<PropensityPoint>, ms: Long): PropensityPoint? {
        // (2026-10) 칸 크기를 숫자로 박지 않고 데이터 간격에서 읽는다 (30초든 1분이든 그대로 맞음)
        val step = if (h.size >= 2) h[1].timestampMs - h[0].timestampMs else MIN_MS
        if (h.isEmpty() || ms < h.first().timestampMs || ms > h.last().timestampMs + step) return null
        return h.minByOrNull { kotlin.math.abs(it.timestampMs - ms) }
    }

    /** 다음 자정(표시 시간대)의 예측 S. 자정이 3시간 안이면 의미가 약해 24시간 뒤 값을 쓴다 */
    private fun nextMidnight(future: List<PredictedPoint>, refMs: Long): Pair<String, Double>? {
        if (future.isEmpty()) return null
        val mid = future.firstOrNull { it.timestampMs - refMs >= 3 * HOUR_MS && minuteOfDay(it.timestampMs) < 15 }
        return if (mid != null) "자정쯤" to mid.predictedS else "24시간 뒤" to future.last().predictedS
    }

    private fun duration(min: Double): String {
        val m = min.toInt(); return if (m % 60 == 0) "${m / 60}시간" else "${m / 60}시간 ${m % 60}분"
    }

    /** 표시 시간대 (화면에서 고른 UTC / 한국 시간) */
    private fun zone(): TimeZone = DisplaySettings.zone.tz
    private fun cal(ms: Long) = java.util.Calendar.getInstance(zone()).apply { timeInMillis = ms }
    private fun hourOf(ms: Long) = cal(ms).get(java.util.Calendar.HOUR_OF_DAY)
    private fun minuteOfDay(ms: Long) = cal(ms).let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }

    /** 표시 시간대 날짜 기준 며칠 차이인지 ("오늘/내일"). 자정~1시는 말할 때 "오늘 밤"이라 그렇게 부른다 */
    private fun dayWord(refMs: Long, ms: Long): String {
        fun dayIndex(t: Long) = Math.floorDiv(t + zone().getOffset(t), DAY_MS)
        val diff = (dayIndex(ms) - dayIndex(refMs)).toInt()
        if (diff == 1 && hourOf(ms) == 0) return "오늘"
        return when (diff) { 0 -> "오늘"; 1 -> "내일"; else -> "${diff}일 뒤" }
    }

    private fun period(h: Int) = when (h) {
        in 0..6 -> "새벽"; in 7..8 -> "아침"; in 9..11 -> "오전"; in 12..17 -> "오후"; in 18..20 -> "저녁"; else -> "밤"
    }
    private fun h12(h: Int) = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }

    /** 분(0~1439) → "새벽 5시 반" 같은 말. 30분 단위로 반올림 */
    fun clockWords(minuteOfDay: Int): String {
        val r = ((minuteOfDay + 15) / 30 * 30) % 1440
        val hh = r / 60; val half = r % 60 == 30
        if (hh == 0 && !half) return "자정"
        if (hh == 0) return "밤 12시 반"
        return "${period(hh)} ${h12(hh)}시" + if (half) " 반" else ""
    }

    private fun circularMeanMinutes(mins: List<Int>): Int {
        val ang = mins.map { it * 2 * Math.PI / 1440 }
        val a = kotlin.math.atan2(ang.sumOf { kotlin.math.sin(it) }, ang.sumOf { kotlin.math.cos(it) })
        return (((a * 1440 / (2 * Math.PI)) + 1440) % 1440).toInt()
    }

    private fun signed(v: Double) = (if (v >= 0) "+" else "") + fmt(v)

    private fun inputNotes(d: DirectInput?, condition: Int?): List<String> {
        val out = mutableListOf<String>()
        if (d?.sleepStartMs != null && d.sleepEndMs != null)
            out += "최근 수면 ${dayTime(d.sleepStartMs)} → ${hhmm(d.sleepEndMs)} : 모델에 반영됨 (업로드 기록과 겹치는 부분은 기록 우선)"
        d?.plannedBedMs?.let { out += "예정 취침 ${dayTime(it)} : 저장됨 — 권장 시각 계산은 준비 중" }
        d?.targetWakeMs?.let { out += "목표 기상 ${dayTime(it)} : 저장됨 — 권장 취침·수면시간 계산은 준비 중" }
        condition?.let { out += "컨디션 $it/5 : 기록됨 — 모델 반영 전 (체감과 모델 예측의 차이 = 잔차 연구에 쓰일 값)" }
        return out
    }

    private fun recommendationText(r: AnalysisResult): String {
        val rec = r.recommendation ?: return "준비 중 — 권장 시각 계산은 모델 확정 후 연결돼요."
        val parts = listOfNotNull(
            rec.recommendedBedTimeMs?.let { "권장 취침 ${hhmm(it)}" },
            rec.recommendedWakeTimeMs?.let { "권장 기상 ${hhmm(it)}" },
            rec.recommendedSleepMin?.let { "권장 수면 ${(it / 60).toInt()}시간 ${(it % 60).toInt()}분" }
        )
        return if (parts.isEmpty()) "준비 중 — 권장 시각 계산은 모델 확정 후 연결돼요." else parts.joinToString(" · ")
    }

    fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    // 화면 시각은 기기 시간대가 아니라 표시 시간대(DisplaySettings.zone, 기본 한국 시간)로 표시한다 (2026-10)
    private fun fmt(pattern: String, tz: TimeZone = zone()) = SimpleDateFormat(pattern, Locale.KOREA).apply { timeZone = tz }
    fun hhmm(ms: Long): String = fmt("HH:mm").format(Date(ms))
    fun dayHour(ms: Long): String = fmt("M/d HH시").format(Date(ms))
    fun dayOnly(ms: Long): String = fmt("M/d").format(Date(ms))
    fun dayTime(ms: Long): String = fmt("M/d(E) HH:mm").format(Date(ms))
}
