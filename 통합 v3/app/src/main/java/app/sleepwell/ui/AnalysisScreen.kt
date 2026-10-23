package app.sleepwell.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.sleepwell.analysis.AnalysisDisplay
import app.sleepwell.analysis.AnalysisPresenter
import app.sleepwell.analysis.AnalysisRunner
import app.sleepwell.analysis.ChartPoint
import app.sleepwell.analysis.DirectInputForm
import app.sleepwell.analysis.DirectInputParser
import app.sleepwell.analysis.Gauge
import app.sleepwell.analysis.Reading
import app.sleepwell.analysis.ReadingKind
import app.sleepwell.health.AnalysisResult
import app.sleepwell.health.DirectInput
import app.sleepwell.health.DisplayZone
import app.sleepwell.health.HealthResult
import app.sleepwell.health.SleepRecord
import app.sleepwell.health.errorMessageOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/*
 * 사용자 화면: 수면 분석 결과. (소빈 · 2026-09-25, 합의안 2절 ⑤~⑧)
 *
 * 흐름: CSV 선택 → (백그라운드) 채윤 파이프라인 → AnalysisResult → AnalysisPresenter 로 정리 → 화면
 *
 * - 채윤 파이프라인 호출은 AnalysisRunner 한 곳에서만 한다.
 * - 값은 임시 모델 결과라 확정값이 아니다 → 화면 맨 위에 "임시 모델" 표시.
 * - 권장 시각은 모델에 없어 "준비 중". futureS 는 채윤 코드가 임시 예측을 넣어서 "임시 예측"으로 표시.
 * - (2026-10) 날짜(밤) 선택: ◀ ▶ 와 달력으로 "○○ 밤"(그날 12:00 ~ 다음 날 12:00)을 골라 본다.
 *   처음에는 가장 최근 밤. "지금 상태"·예측은 가장 최근 밤에서만 보인다.
 *   가장 최근 밤은 업로드 때 계산한 결과(마지막 기록 기준 앞 7일)를 쓰고,
 *   지난 밤을 고르면 그 밤의 끝을 기준 시각으로 다시 계산한다(그 앞 7일). 고를 수 있는 밤 = 데이터 전체 범위. (2026-10)
 */

/** 계산 결과 + 그때 쓴 입력. 밤·시간대를 바꿀 때 같은 입력으로 화면만 다시 정리하기 위해 함께 둔다. */
private data class Computed(val result: AnalysisResult, val direct: DirectInput?, val condition: Int?)

private val COLOR_S = Color(0xFF3D6FB6)      // 수면 압력: 파랑
private val COLOR_C = Color(0xFFE8853A)      // 일주기 리듬: 주황
private val COLOR_P = Color(0xFF7B4FC9)      // 수면 성향: 보라
private val COLOR_SLEEP_BAND = Color(0x1F33507A)
private val COLOR_MANUAL_BAND = Color(0x297B4FC9)
private val COLOR_SOFT_BOX = Color(0xFFF3F4F7)       // 체크 설명·경향 문장 배경
private val COLOR_NOTE = Color(0xFF777777)

/** 에뮬레이터에는 파일이 없어서, 앱 안에 넣어둔 샘플로도 시험할 수 있게 한다 */
private const val SAMPLE_SMALL = "sleep_stage_sample_202609.csv"   // 혜지 데이터 중 2026-09 기록만
private const val SAMPLE_FULL = "sleep_stage_cleaned.csv"          // 혜지 데이터 전체 (2024-09 ~ 2026-09)

@Composable
fun AnalysisScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var display by remember { mutableStateOf<AnalysisDisplay?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var computed by remember { mutableStateOf<Computed?>(null) }
    var selectedNight by remember { mutableStateOf<Long?>(null) }   // null = 가장 최근 밤
    var pickMessage by remember { mutableStateOf<String?>(null) }
    var nightLoading by remember { mutableStateOf(false) }          // 지난 밤 다시 계산 중
    var nightError by remember { mutableStateOf<String?>(null) }    // 지난 밤을 계산할 수 없을 때 이유
    val zone = UiZone.current                                        // 표시 시간대 (바꾸면 아래 LaunchedEffect 가 다시 정리)

    // ── 4단계 직접 입력 상태 ──
    var fileReady by remember { mutableStateOf(false) }          // 업로드한 파일이 캐시에 있는가
    var form by remember { mutableStateOf(DirectInputForm()) }
    var formErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var appliedInput by remember { mutableStateOf<DirectInput?>(null) }
    var appliedCondition by remember { mutableStateOf<Int?>(null) }
    var lastRecordEnd by remember { mutableStateOf<Long?>(null) }   // 업로드 기록의 마지막 기상

    val uploaded = File(context.cacheDir, "upload_sleep_stage.csv")

    // 캐시에 있는 파일 + 직접 입력으로 계산 (백그라운드)
    fun runAnalysis(direct: DirectInput?, condition: Int?) {
        loading = true
        error = null
        display = null
        computed = null
        pickMessage = null
        scope.launch {
            val result = withContext(Dispatchers.Default) { AnalysisRunner.runFile(uploaded, direct) }
            when (result) {
                is HealthResult.Success -> {
                    if (direct == null) lastRecordEnd = result.data.sleepRecords.maxOfOrNull { it.endMs }   // 업로드 직후 1회 기록
                    selectedNight = null                     // 새로 계산하면 가장 최근 밤부터
                    computed = Computed(result.data, direct, condition)
                }
                else -> error = result.errorMessageOrNull()
            }
            loading = false
        }
    }

    // 업로드 직후 백그라운드에서 계산 (합의안 3-2). 새 파일이면 직접 입력은 비운다.
    fun analyze(name: String, copyTo: (File) -> Unit) {
        sourceName = name
        form = DirectInputForm(); formErrors = emptyList(); appliedInput = null; appliedCondition = null; lastRecordEnd = null
        fileReady = try { copyTo(uploaded); true } catch (e: Exception) {
            error = "파일을 읽지 못했어요: ${e.message}"; display = null; false
        }
        if (fileReady) runAnalysis(null, null)
    }

    // 입력이 바뀌면 다시 계산 (합의안 5-1 #3 "사용자 조건 변경 시 재계산")
    fun applyForm() {
        val parsed = DirectInputParser.parse(form, lastRecordEnd)
        formErrors = parsed.errors
        if (!parsed.ok) return
        appliedInput = parsed.input
        appliedCondition = form.condition
        runAnalysis(appliedInput, appliedCondition)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) analyze("선택한 파일") { dst -> copyUri(context, uri, dst) }
    }

    // 지난 밤 계산 결과 저장 (같은 밤을 다시 고르면 다시 계산하지 않음). 새로 계산(파일·직접 입력)하면 비운다.
    val nightCache = remember(computed) { mutableMapOf<Long, HealthResult<AnalysisResult>>() }

    // 고른 밤·표시 시간대가 바뀌면:
    //  - 가장 최근 밤 → 업로드 때 계산한 결과(마지막 기록 기준 앞 7일)를 그대로 정리
    //  - 지난 밤     → 그 밤의 끝(다음 날 정오)을 기준 시각으로 다시 계산(그 앞 7일) 후 정리 (2026-10)
    LaunchedEffect(computed, selectedNight, zone) {
        val c = computed ?: return@LaunchedEffect
        val base = c.result
        val latestRef = base.scHistory.lastOrNull()?.timestampMs ?: base.calculationTimeMs
        val dataStart = listOfNotNull(base.sleepRecords.minOfOrNull { it.startMs }, base.scHistory.firstOrNull()?.timestampMs).minOrNull()
        val latestStart = AnalysisPresenter.nightStartOf(latestRef)
        val sel = selectedNight?.let { AnalysisPresenter.nightStartOf(it) }
        nightError = null
        nightLoading = false
        if (sel == null || sel >= latestStart) {
            display = withContext(Dispatchers.Default) {
                AnalysisPresenter.build(base, null, direct = c.direct, condition = c.condition, latestRefMs = latestRef, dataStartMs = dataStart)
            }
            return@LaunchedEffect
        }
        nightLoading = true
        val res = nightCache[sel] ?: withContext(Dispatchers.Default) {
            AnalysisRunner.runFileAt(uploaded, c.direct, AnalysisPresenter.nightEndOf(sel))
        }.also { nightCache[sel] = it }
        display = withContext(Dispatchers.Default) {
            if (res is HealthResult.Success)
                AnalysisPresenter.build(res.data, sel, direct = c.direct, condition = c.condition, latestRefMs = latestRef, dataStartMs = dataStart)
            else
                // 계산이 안 되는 밤: 날짜 고르기는 계속 쓸 수 있게 밤 목록만 업로드 결과로 만든다
                AnalysisPresenter.build(base, sel, direct = c.direct, condition = c.condition, latestRefMs = latestRef, dataStartMs = dataStart)
        }
        if (res !is HealthResult.Success) {
            nightError = if (res is HealthResult.Unavailable && res.reason.contains("기록이 더 필요"))
                "이 밤 앞 7일 동안 수면 기록이 없어서 계산할 수 없어요. (기록이 없으면 내내 깨어 있던 것으로 계산돼 의미가 없어요)"
            else res.errorMessageOrNull()
        }
        nightLoading = false
    }

    // 달력에서 날짜 고르기. 고른 날짜의 "밤"(그날 12:00 ~ 다음 날 12:00)으로 이동한다.
    fun openDatePicker(d: AnalysisDisplay) {
        val nights = d.nights
        val cur = d.night ?: return
        if (nights.isEmpty()) return
        val tz = zone.tz
        val c = Calendar.getInstance(tz).apply { timeInMillis = cur.startMs }
        val dlg = android.app.DatePickerDialog(context, { _, y, m, day ->
            val pick = Calendar.getInstance(tz).apply { clear(); set(y, m, day, 12, 0, 0) }.timeInMillis
            val target = nights.firstOrNull { it.startMs == pick }
            if (target != null) { selectedNight = target.startMs; pickMessage = null }
            else pickMessage = "그 날짜는 계산 범위 밖이에요. ${nights.first().label} ~ ${nights.last().label} 중에서 골라 주세요."
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        // 달력은 기기 시간대로 날짜를 그리므로, 고를 수 있는 범위를 같은 날짜의 기기 시간대 시각으로 바꿔서 건다
        fun deviceDay(ms: Long, endOfDay: Boolean): Long {
            val src = Calendar.getInstance(tz).apply { timeInMillis = ms }
            return Calendar.getInstance().apply {
                clear()
                set(src.get(Calendar.YEAR), src.get(Calendar.MONTH), src.get(Calendar.DAY_OF_MONTH),
                    if (endOfDay) 23 else 0, if (endOfDay) 59 else 0, 0)
            }.timeInMillis
        }
        dlg.datePicker.minDate = deviceDay(nights.first().startMs, false)
        dlg.datePicker.maxDate = deviceDay(nights.last().startMs, true)
        dlg.show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("수면 분석", style = MaterialTheme.typography.headlineSmall)
        Text(
            "삼성헬스 수면 단계 CSV 를 넣으면 수면 압력과 일주기 리듬을 계산해 보여줘요.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { picker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/octet-stream")) },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("CSV 파일 선택") }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { analyze("샘플: 2026-09 기록") { dst -> copyAsset(context, SAMPLE_SMALL, dst) } },
                enabled = !loading
            ) { Text("샘플: 9월 기록") }
            OutlinedButton(
                onClick = { analyze("샘플: 전체 기록(2년)") { dst -> copyAsset(context, SAMPLE_FULL, dst) } },
                enabled = !loading
            ) { Text("샘플: 전체(2년)") }
        }

        if (fileReady) {
            Spacer(Modifier.height(16.dp))
            DirectInputCard(
                lastRecordEnd = lastRecordEnd,
                form = form,
                onChange = { form = it },
                errors = formErrors,
                enabled = !loading,
                onApply = { applyForm() }
            )
        }

        if (loading) {
            Spacer(Modifier.height(16.dp))
            Row {
                CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
                Text("  분석 중… (${sourceName ?: ""})", style = MaterialTheme.typography.bodyMedium)
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            // 잘못된 파일(예: XML)을 고르면 오류 문구에 파일 첫 줄 전체가 들어와 화면을 덮는다 → 앞부분만 보여준다
            val shown = if (it.length > 200) it.take(200) + " …" else it
            InfoCard(
                "분석하지 못했어요",
                listOf(shown, "삼성헬스 수면 단계 CSV(start_time, end_time, stage 열이 있는 파일)인지 확인해 주세요."),
                Color(0xFFFDE7E9)
            )
        }

        display?.let { d ->
            Spacer(Modifier.height(16.dp))
            NightSelector(
                d = d,
                zone = zone,
                message = pickMessage,
                onSelect = { selectedNight = it; pickMessage = null },
                onPickDate = { openDatePicker(d) }
            )
            Spacer(Modifier.height(12.dp))
            when {
                nightLoading -> Row {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
                    Text("  이 밤 기준으로 계산 중…", style = MaterialTheme.typography.bodyMedium)
                }
                nightError != null -> InfoCard("이 밤은 계산할 수 없어요", listOf(nightError ?: ""), Color(0xFFFDE7E9))
                else -> AnalysisContent(d, sourceName ?: "", zone)
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

/**
 * 날짜(밤) 고르기: ◀ [9/13(일) 밤 · 9/13 12:00 ~ 9/14 12:00 KST] ▶  + 달력 + (지난 밤이면) 가장 최근 밤으로
 */
@Composable
private fun NightSelector(
    d: AnalysisDisplay,
    zone: DisplayZone,
    message: String?,
    onSelect: (Long) -> Unit,
    onPickDate: () -> Unit
) {
    val night = d.night ?: return
    val idx = d.nights.indexOfFirst { it.startMs == night.startMs }
    val prev = d.nights.getOrNull(idx - 1)
    val next = d.nights.getOrNull(idx + 1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { prev?.let { onSelect(it.startMs) } }, enabled = prev != null) { Text("◀") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(night.label + if (night.isLatest) " (가장 최근)" else "", style = MaterialTheme.typography.titleMedium)
                    Text("${night.rangeText} (${zone.short})", style = MaterialTheme.typography.labelSmall, color = COLOR_NOTE)
                }
                OutlinedButton(onClick = { next?.let { onSelect(it.startMs) } }, enabled = next != null) { Text("▶") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onPickDate) { Text("날짜 고르기") }
                if (!night.isLatest) TextButton(onClick = { onSelect(d.nights.last().startMs) }) { Text("가장 최근 밤으로") }
            }
            Text(
                "하루 = 그날 낮 12시 ~ 다음 날 낮 12시. 새벽에 잔 잠도 전날 밤에 들어가요.",
                style = MaterialTheme.typography.labelSmall, color = COLOR_NOTE
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AnalysisContent(d: AnalysisDisplay, sourceName: String, zone: DisplayZone) {
    val latest = d.night?.isLatest != false
    if (d.isTemporaryModel) {
        InfoCard(
            "임시 모델 — 분석 화면 출력 확인용",
            listOf(
                "모델: ${d.modelLabel}",
                "값과 해석 기준은 아직 확정되지 않았어요. 모델이 확정되면 교체돼요."
            ),
            Color(0xFFFFF4E5)
        )
        Spacer(Modifier.height(12.dp))
    }

    Text(
        "데이터: $sourceName · 기준 시각 ${AnalysisPresenter.dayTime(d.referenceMs)} (${zone.short}) " +
                if (d.manualSleep != null && d.manualSleep.endMs >= d.referenceMs - 60_000L) "(직접 입력한 기상)" else "(마지막 기록)",
        style = MaterialTheme.typography.bodySmall
    )
    if (d.night?.isPartial == true) {
        Spacer(Modifier.height(8.dp))
        InfoCard(
            "기록이 시작된 직후의 밤",
            listOf("수면 압력은 이전 기록에 이어서 계산되는 값이라, 그 앞 기록이 없는 첫 밤은 실제와 다를 수 있어요. 참고만 해 주세요."),
            Color(0xFFFFF4E5)
        )
    } else if (d.windowSleepCount < 3) {
        Spacer(Modifier.height(8.dp))
        InfoCard(
            "앞 7일 기록이 적어요 (${d.windowSleepCount}번)",
            listOf("기록이 없는 날은 깨어 있던 것으로 계산돼서 수면 압력이 실제보다 높게 나와요. 참고만 해 주세요."),
            Color(0xFFFFF4E5)
        )
    }
    if (d.inputNotes.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        InfoCard("직접 입력 반영", d.inputNotes, Color(0xFFE8F0FB))
    }

    // ── 이 밤의 수면 (모든 밤) ───────────────────────────────
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("이 밤의 수면", style = MaterialTheme.typography.titleMedium)
            if (d.nightSleeps.isEmpty()) Text("이 밤에는 수면 기록이 없어요.", style = MaterialTheme.typography.bodySmall)
            d.nightSleeps.forEach { SleepRecordRow(it, zone) }
        }
    }

    // ── 현재 상태 (가장 최근 밤에서만) ────────────────────────
    // (2026-09-26) 체크 설명 → 값별 설명. 높음/보통/낮음 임의 기준은 없애고 실제 값·본인 기록으로 설명
    // (2026-10) 지난 밤을 보고 있을 때 "지금"은 의미가 없어 숨긴다
    if (latest) {
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("지금 상태", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SoftBox {
                Text(buildAnnotatedString {
                    append("✓ "); withStyle(SpanStyle(color = COLOR_S, fontWeight = FontWeight.Bold)) { append("수면 압력") }
                    append("은 깨어 있던 시간만큼 쌓이는 피로를 나타내는 지표예요.")
                }, style = MaterialTheme.typography.bodySmall)
                Text(buildAnnotatedString {
                    append("✓ "); withStyle(SpanStyle(color = COLOR_C, fontWeight = FontWeight.Bold)) { append("몸 시계") }
                    append("는 하루 주기로 바뀌는 몸의 리듬을 나타내는 지표예요.")
                }, style = MaterialTheme.typography.bodySmall)
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("→ 둘을 합친 ")
                        withStyle(SpanStyle(color = COLOR_P)) { append("잠들기 쉬운 정도") }
                        append("가 클수록 잠들기 쉬워요.")
                    }
                }, style = MaterialTheme.typography.bodySmall)
            }
            d.readings.forEach { r -> ReadingBlock(r) }
            Spacer(Modifier.height(8.dp))
            Text("※ 값은 임시 모델로 계산했어요.", style = MaterialTheme.typography.labelSmall, color = COLOR_NOTE)
        }
    }
    }

    // ── 그래프 ───────────────────────────────────────────────
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "${d.night?.label ?: ""}의 변화" + if (latest && d.future.isNotEmpty()) " (지금 이후는 예측)" else "",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend("수면 압력", COLOR_S)
                Legend("몸 시계", COLOR_C)
                Legend("잠들기 쉬운 정도", COLOR_P)
            }
            Spacer(Modifier.height(6.dp))
            if (d.history.isEmpty()) {
                Text("표시할 값이 없어요.", style = MaterialTheme.typography.bodySmall)
            } else {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                ) { drawAnalysisChart(d) }
            }
            if (d.patternLines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SoftBox { d.patternLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
            d.easiestSleepMs?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "안 자고 버틴다면 가장 졸린 때: ${AnalysisPresenter.dayTime(it)} (임시 예측)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // 그래프 표시 설명 (필요한 것만)
            val notes = buildList {
                if (latest) add(if (d.future.isEmpty()) "앞으로의 변화: 준비 중" else "점선 = 지금부터 계속 깨어 있다고 가정한 예측")
                if (d.manualSleep != null) add("연보라 = 직접 입력한 수면")
                if (d.gapBands.isNotEmpty()) add("빗금 = 기록 없음 (깨어 있던 것으로 계산돼 실제보다 높게 보여요)")
            }
            Spacer(Modifier.height(4.dp))
            notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = COLOR_NOTE) }
        }
    }

    if (!latest) return     // 권장 시각·최근 기록 목록은 가장 최근 밤에서만

    // ── 권장 시각 ─────────────────────────────────────────────
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("권장 취침·기상 시각, 권장 수면시간", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(d.recommendationText, style = MaterialTheme.typography.bodyMedium)
        }
    }

    // ── 최근 수면 기록 ───────────────────────────────────────
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("최근 수면 기록", style = MaterialTheme.typography.titleMedium)
            if (d.recentSleeps.isEmpty()) Text("기록이 없어요.", style = MaterialTheme.typography.bodySmall)
            d.recentSleeps.forEach { SleepRecordRow(it, zone) }
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")   // zone: 시간대를 바꾸면 이 줄도 다시 그리게 하려고 받는다 (글자는 AnalysisPresenter 가 표시 시간대로 만든다)
private fun SleepRecordRow(r: SleepRecord, zone: DisplayZone) {
    val total = r.totalSleepMin
    val parts = listOf("LIGHT", "DEEP", "REM").joinToString(" · ") { k ->
        val m = r.stageSummary[k] ?: 0.0
        val pct = if (total > 0) m * 100 / total else 0.0
        "$k ${pct.toInt()}%"
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "${AnalysisPresenter.dayTime(r.startMs)} → ${AnalysisPresenter.hhmm(r.endMs)}",
        style = MaterialTheme.typography.labelLarge
    )
    Text(
        "잔 시간 ${(total / 60).toInt()}시간 ${(total % 60).toInt()}분 · $parts",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun Legend(label: String, color: Color) {
    Row {
        Canvas(Modifier.width(14.dp).height(10.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx())
        }
        Text(" $label", style = MaterialTheme.typography.labelSmall)
    }
}

/** 연회색 둥근 상자 (체크 설명·경향 문장) */
@Composable
private fun SoftBox(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = COLOR_SOFT_BOX), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
    }
}

private fun colorOf(k: ReadingKind) = when (k) { ReadingKind.S -> COLOR_S; ReadingKind.C -> COLOR_C; ReadingKind.P -> COLOR_P }

/** 지금 상태의 값 하나: 제목·큰 값 → (막대) → 설명 → (세부) → (작은 주석) */
@Composable
private fun ReadingBlock(r: Reading) {
    val color = colorOf(r.kind)
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(r.title, style = MaterialTheme.typography.labelLarge, color = color)
        Text(r.value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
    }
    r.gauge?.let { GaugeBar(it, color) }
    Text(r.meaning, style = MaterialTheme.typography.bodySmall)
    r.details.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
    r.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = COLOR_NOTE) }
}

/** 범위 막대: 연한 색 → 진한 색, 지금 값 위치에 검은 표시 */
@Composable
private fun GaugeBar(g: Gauge, color: Color) {
    Spacer(Modifier.height(4.dp))
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val barH = 8.dp.toPx(); val top = (size.height - barH) / 2
        drawRoundRect(
            Brush.horizontalGradient(listOf(color.copy(alpha = 0.15f), color)),
            Offset(0f, top), Size(size.width, barH), CornerRadius(barH / 2)
        )
        val f = ((g.value - g.min) / (g.max - g.min)).coerceIn(0.0, 1.0).toFloat()
        val mx = (size.width * f).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
        drawRoundRect(Color(0xFF1F2933), Offset(mx - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(g.leftLabel, style = MaterialTheme.typography.labelSmall, color = COLOR_NOTE)
        Text(g.rightLabel, style = MaterialTheme.typography.labelSmall, color = COLOR_NOTE)
    }
}

@Composable
private fun DirectInputCard(
    lastRecordEnd: Long?,
    form: DirectInputForm,
    onChange: (DirectInputForm) -> Unit,
    errors: List<String>,
    enabled: Boolean,
    onApply: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("직접 입력 (선택)", style = MaterialTheme.typography.titleMedium)
            Text(
                "형식 ${DirectInputParser.PATTERN} (예: 2026-09-15 23:30), ${UiZone.current.label} 기준. 업로드 기록 이후의 밤을 넣으면 그 시각까지 이어서 계산해요.",
                style = MaterialTheme.typography.bodySmall
            )
            lastRecordEnd?.let { last ->
                Spacer(Modifier.height(6.dp))
                // 입력 가능한 범위를 미리 보여준다 (뒤로 넘으면 기록 없는 날이 전부 '깨어 있음'으로 계산돼 거부, 앞으로 넘으면 계산 범위 밖이라 무시됨)
                Text(
                    "이 파일의 마지막 기록: ${DirectInputParser.toText(last)} → " +
                            "기상 시각은 ${DirectInputParser.toText(DirectInputParser.earliestWakeAllowed(last))} 이후 ~ " +
                            "${DirectInputParser.toText(DirectInputParser.latestWakeAllowed(last))} 이전이어야 반영돼요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF3D6FB6)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("최근 수면 (모델에 반영)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.bedText, onValueChange = { onChange(form.copy(bedText = it)) },
                    label = { Text("취침") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = form.wakeText, onValueChange = { onChange(form.copy(wakeText = it)) },
                    label = { Text("기상") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("앞으로의 계획 (저장만 — 권장 시각 계산 준비 중)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.plannedBedText, onValueChange = { onChange(form.copy(plannedBedText = it)) },
                    label = { Text("예정 취침") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = form.targetWakeText, onValueChange = { onChange(form.copy(targetWakeText = it)) },
                    label = { Text("목표 기상") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("오늘 컨디션 (기록만 — 모델 반영 전)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { v ->
                    FilterChip(
                        selected = form.condition == v,
                        onClick = { onChange(form.copy(condition = if (form.condition == v) null else v)) },
                        label = { Text("$v") }
                    )
                }
            }
            Text("1 = 매우 피곤 · 5 = 매우 개운", style = MaterialTheme.typography.labelSmall, color = Color(0xFF777777))
            if (errors.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                errors.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onApply, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("입력 반영해서 다시 분석")
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>, bg: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            lines.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 그래프
// ─────────────────────────────────────────────────────────────
private fun DrawScope.drawAnalysisChart(d: AnalysisDisplay) {
    // (2026-09-26) 그래프 안 표시 추가: "지금 → 예측", "잠", ▲(몸 시계 꼭대기), ↑졸림/↓개운함, 기록 없음 빗금, 날짜 눈금
    val leftPad = 30.dp.toPx()
    val topPad = 18.dp.toPx()        // "지금" 표시 자리
    val bottomPad = 16.dp.toPx()
    val plotW = size.width - leftPad
    val plotH = size.height - bottomPad - topPad
    val plotBottom = topPad + plotH

    val all = d.history + d.future
    // (2026-10) 가로 범위 = 고른 밤 (12:00 ~ 다음 날 12:00). 밤 정보가 없으면 예전처럼 데이터 범위.
    val startMs = d.night?.startMs ?: d.history.first().timeMs
    val endMs = d.night?.endMs ?: maxOf(all.last().timeMs, d.referenceMs + 1)
    val span = (endMs - startMs).coerceAtLeast(1L).toFloat()

    val values = all.flatMap { listOf(it.s, it.c, it.propensity) }
    val lo = (values.min() - 0.05).toFloat()
    val hi = (values.max() + 0.05).toFloat()
    val range = (hi - lo).coerceAtLeast(0.1f)

    fun x(ms: Long) = leftPad + plotW * (ms - startMs) / span
    fun y(v: Double) = topPad + plotH - plotH * (v.toFloat() - lo) / range
    fun text(s: String, px: Float, py: Float, color: String = "#666666", sizeSp: Float = 9f, bold: Boolean = false,
             align: android.graphics.Paint.Align = android.graphics.Paint.Align.LEFT) =
        drawIntoCanvas { c -> c.nativeCanvas.drawText(s, px, py, chartPaint(density, color, sizeSp, bold, align)) }

    // 기록 없음 (빗금) — 하루 넘게 수면 기록이 없어 '깨어 있음'으로 계산된 구간
    d.gapBands.forEach { g ->
        val xs = x(g.startMs.coerceIn(startMs, endMs)); val xe = x(g.endMs.coerceIn(startMs, endMs))
        if (xe - xs > 4f) {
            clipRect(xs, topPad, xe, plotBottom) {
                val gap = 7.dp.toPx()
                var k = xs - plotH
                while (k < xe) {
                    drawLine(Color(0x33808A96), Offset(k, plotBottom), Offset(k + plotH, topPad), 1.dp.toPx())
                    k += gap
                }
            }
            text("기록 없음", (xs + xe) / 2, plotBottom - 4.dp.toPx(), align = android.graphics.Paint.Align.CENTER)
        }
    }

    // 잔 시간 배경 + "잠"
    fun band(s0: Long, e0: Long, color: Color) {
        val xs = x(s0.coerceIn(startMs, endMs)); val xe = x(e0.coerceIn(startMs, endMs))
        if (xe > xs) {
            drawRect(color, Offset(xs, topPad), Size(xe - xs, plotH))
            if (xe - xs > 8.dp.toPx()) text("잠", (xs + xe) / 2, topPad + 11.dp.toPx(), "#33507A", 9f, true, android.graphics.Paint.Align.CENTER)
        }
    }
    d.manualSleep?.let { band(it.startMs, it.endMs, COLOR_MANUAL_BAND) }   // 직접 입력한 수면은 연보라
    d.sleepBands.forEach { band(it.startMs, it.endMs, COLOR_SLEEP_BAND) }

    // 가로 눈금 (0, 0.5, 1 중 범위 안에 있는 것)
    listOf(0.0, 0.5, 1.0).filter { it.toFloat() in lo..hi }.forEach { v ->
        val yy = y(v)
        drawLine(Color(0x22000000), Offset(leftPad, yy), Offset(size.width, yy), 1f)
        text(AnalysisPresenter.fmt(v), 2f, yy + 4.dp.toPx())
    }

    // 세로 눈금: 표시 시간대 기준 3시간마다 선, 6시간마다 시각 글자. 자정에는 날짜를 함께 쓴다.
    // (2026-10) 하루(24시간) 그래프로 바뀌어 12시간 → 3시간 간격. 시간대는 화면에서 고른 표시 시간대.
    val step = 3 * 3_600_000L
    val offset = app.sleepwell.health.DisplaySettings.zone.tz.getOffset(startMs).toLong()
    var t = Math.floorDiv(startMs + offset + step - 1, step) * step - offset
    while (t <= endMs) {
        val px = x(t)
        val localHour = (Math.floorMod(t + offset, 24 * 3_600_000L) / 3_600_000L).toInt()
        val midnight = localHour == 0
        drawLine(Color(if (midnight) 0x22000000 else 0x0D000000), Offset(px, topPad), Offset(px, plotBottom), 1f)
        if (localHour % 6 == 0) {
            val label = if (midnight) AnalysisPresenter.dayOnly(t) else "${localHour}시"
            val align = when {
                px < leftPad + 12.dp.toPx() -> android.graphics.Paint.Align.LEFT
                px > size.width - 12.dp.toPx() -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            text(label, px, size.height - 2.dp.toPx(), align = align)
        }
        t += step
    }

    // ↑ 졸림 / ↓ 개운함
    text("↑ 졸림", leftPad + 3.dp.toPx(), topPad + 10.dp.toPx(), "#555555")
    text("↓ 개운함", leftPad + 3.dp.toPx(), plotBottom - 14.dp.toPx(), "#555555")

    // 선
    val solid = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    val solidP = Stroke(width = 2.6f.dp.toPx(), cap = StrokeCap.Round)
    fun dashed(w: Float) = Stroke(width = w, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f))
    fun line(points: List<ChartPoint>, pick: (ChartPoint) -> Double, color: Color, style: Stroke) {
        if (points.isEmpty()) return
        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(p.timeMs), y(pick(p))) else path.lineTo(x(p.timeMs), y(pick(p)))
        }
        drawPath(path, color, style = style)
    }
    line(d.history, { it.c }, COLOR_C, solid)
    line(d.history, { it.s }, COLOR_S, solid)
    line(d.history, { it.propensity }, COLOR_P, solidP)
    line(d.future, { it.c }, COLOR_C.copy(alpha = 0.7f), dashed(2.dp.toPx()))
    line(d.future, { it.s }, COLOR_S.copy(alpha = 0.7f), dashed(2.dp.toPx()))
    line(d.future, { it.propensity }, COLOR_P.copy(alpha = 0.7f), dashed(2.6f.dp.toPx()))

    // ▲ 몸 시계 꼭대기 (하루 중 몸이 가장 잠을 원하는 때)
    val cMax = all.maxOf { it.c }
    for (i in 1 until all.size - 1) {
        val p = all[i]
        if (p.c >= all[i - 1].c && p.c > all[i + 1].c && p.c > cMax * 0.9) {
            val px = x(p.timeMs); val py = y(p.c); val r = 4.dp.toPx()
            val tri = Path().apply { moveTo(px - r, py - r - 2f); lineTo(px + r, py - r - 2f); lineTo(px, py - 1f); close() }
            drawPath(tri, COLOR_C)
        }
    }

    // 지금 (세로선) + "지금 → 예측" — 고른 밤 안에 '지금'이 있을 때만 (가장 최근 밤)
    if (d.night?.isLatest == false || d.referenceMs !in startMs..endMs) return
    val rx = x(d.referenceMs)
    drawLine(Color(0x99000000), Offset(rx, topPad), Offset(rx, plotBottom), 1.5f)
    val pillW = 30.dp.toPx(); val pillH = 14.dp.toPx()
    drawRoundRect(Color(0xFF1F2933), Offset(rx - pillW / 2, 1.dp.toPx()), Size(pillW, pillH), CornerRadius(pillH / 2))
    text("지금", rx, 1.dp.toPx() + 10.5f.dp.toPx(), "#FFFFFF", 9f, true, android.graphics.Paint.Align.CENTER)
    if (d.future.isNotEmpty()) text("→ 예측", rx + pillW / 2 + 3.dp.toPx(), 1.dp.toPx() + 10.5f.dp.toPx(), "#1F2933", 9f, true)
}

private fun chartPaint(
    density: Float,
    colorHex: String = "#666666",
    sizeSp: Float = 9f,
    bold: Boolean = false,
    align: android.graphics.Paint.Align = android.graphics.Paint.Align.LEFT
) = android.graphics.Paint().apply {
    color = android.graphics.Color.parseColor(colorHex)
    textSize = sizeSp * density
    isFakeBoldText = bold
    textAlign = align
    isAntiAlias = true
}

// ─────────────────────────────────────────────────────────────
// 파일 복사 (파이프라인이 지금 File 을 받기 때문. 채윤이 InputStream 입력으로 바꾸면 불필요해짐)
// ─────────────────────────────────────────────────────────────
private fun copyUri(context: Context, uri: Uri, dst: File) {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("선택한 파일을 열 수 없습니다.")
    input.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
}

private fun copyAsset(context: Context, name: String, dst: File) {
    context.assets.open(name).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
}
