# HealthDataSource 인터페이스 합의안 (v1.3)

작성: 소빈 / 2026-09-18

> **v1.3 변경점 (⚠ 이것만 보면 됨):** 채윤 SDK 회신 반영. **인터페이스(2·3절)는 그대로** — 바꿀 항목이 0개였음.
> 1. **수면 단계는 4개만 옴** (Wake/Light/Deep/REM). `DataType`/`SleepType`/`StageType`에 N1/N2가 존재하지 않음
>    → **`Light → N2` 매핑 확정**, `meta.json`에 규칙 기록. 없는 정보를 만들지 않는 원칙상 N1/N2 임의 분할 안 함.
>    → **앱 데이터의 `stage_label` 값 집합은 `{0, 2, 3, 4}`. N1(=1)은 구조적으로 안 나옴.**
>    ★ **가드레일:** 워치 단계는 **정답(`y_true`) 자리에 두지 않는다.** 평가코드가 5단계를 가정하므로,
>      정답 자리에 넣으면 없는 클래스가 0점으로 평균에 들어가 **macro-F1이 조용히 왜곡됨.**
> 2. **파트너 승인 불필요** — 읽기 전용은 Public tier, Developer Mode로 즉시 테스트 가능.
>    → 이전 버전의 "승인 못 받으면 MVP가 막힌다" 리스크 **삭제.**
> 3. **에뮬레이터 미지원(실기기 필수)** / Samsung Health 6.30.2+ / **Android 10(API 29)+**
>    → 현재 프로젝트가 `minSdk 26`이라 **29로 올려야 함**(채윤 폰 버전 확인 후).
> 4. **워치 소스 필터는 됨**(`fromDeviceType(WATCH)` + `fromPlatform()`), 단 **Watch5 모델 단위 특정은 미확인**
>    → 1차 범위 제외. 대신 `DeviceInfo.rawSourceId`에 SDK 원본 식별자를 **보존**한다.
> 5. **심박 = 연속 시계열 / 걸음 = 구간 합계(Aggregate only)** → 기존 모델 그대로 맞음, 수정 0.
>    단 심박 간격이 워치 설정에 좌우됨(연속 vs 10분) → 그래프의 결측 판정을 **표본 간격 중앙값 기반 자동 추정**으로 수정함.
> 6. **★ 담당 확정: 구현·실행 = 채윤 / 규격·검증 = 소빈.**
>    - 채윤: `SamsungHealthSource.kt` **작성**(채윤이 새로 만드는 유일한 파일) → 본인 폰에서 실행 →
>      **소빈이 만들어 둔 내보내기 버튼을 눌러 실제 데이터로 파일 1회 뽑기**
>    - 소빈: 창구 규격 유지 + **export 기능 자체(이미 완성)** + **뽑힌 파일을 앱 밖 검사 코드(`MVP_export검증.py` 11항목)로 검증**
>    - ※ **④ export는 소빈 몫 그대로다.** `Exporters.kt`·`EpochAggregator.kt`는 이미 완성돼 있고,
>      채윤이 맡는 건 "실제 데이터가 있는 유일한 기기에서 그 버튼을 한 번 눌러주는 것"뿐이다.
>      실제 Watch 5 데이터는 채윤 Samsung Health에만 있고 에뮬레이터는 SDK가 막혀 있어, 소빈이 대신할 수 없다.
>    - 근거: 채윤이 Claude Code를 쓰므로 **쓴 사람이 그 자리에서 돌려볼 수 있어 왕복이 0.**
>      소빈이 쓰면 에뮬레이터가 막혀 있어 한 번도 실행할 수 없음.
>    - ★ **검증을 소빈이 맡는 것이 조건.** 아래 3개는 **컴파일이 통과하면서 값만 틀린다:**
>      ① `sourceTag` 누락 → 실제 데이터가 파일명 `_FAKE`로 나감
>      ② 시각을 UTC epoch millis 아닌 기준으로 넣음 → `night`/`epoch_idx` 9시간 어긋남(8월 사고와 동일)
>      ③ 실패를 예외로 던짐 → 앱이 죽음
>      컴파일러가 못 잡으므로 **만든 사람과 검사하는 사람을 분리**한다.
>
> **v1.2 변경점:** export 파일 `epoch_30s.csv`의 **`night` / `epoch_idx` 정의 변경**
> \+ `session_id` 열 추가. → 4절 참고.
> **채윤이 구현에는 영향 없음** — 이 두 값은 `SleepSession`에 없고 내 쪽에서 시각으로부터 계산하는 값이야.
> 인터페이스(2·3절)와 채윤이 회신 항목(5절)은 v1.1과 동일해.
>
> **v1.1 변경점:** 시각 타입을 `java.time.Instant` → **`Long`(epoch milliseconds)** 로 변경.
> 이유: `java.time`은 API 26 미만에서 **core library desugaring**(Gradle 설정)이 필요해서, 그것만으로 빌드 설정을 건드려야 함.
> 우리 1차 목표는 "Gradle 손 안 대고 템플릿 기본값으로 돌리기"라서 `Long`이 안전함.
> 채윤 쪽에서 SDK가 `Instant`를 주면 `.toEpochMilli()` 한 번만 붙이면 됨.

대상: 채윤
목적: **분담표의 "공통 데이터 형식 합의 → 각자 동시 개발"에서 그 '공통 형식'을 확정하기 위한 초안**

---

## 0. 왜 이걸 먼저 정해야 하나

우리 분담이 이렇게 되어 있어:

```
        ┌──────────────── HealthDataSource (공통 입구) ────────────────┐
        │                                                              │
  SamsungHealthSource (채윤)                        FakeDataSource (소빈)
   = 실제 Samsung Health SDK                         = 가상 데이터 생성
```

즉 **화면·그래프·export(소빈)는 "어떤 타입이 들어오는지"만 알면 실제 SDK 없이도 다 만들 수 있어.** 반대로 채윤이는 "어떤 타입으로 변환해서 내보내야 하는지"만 알면 돼.

그래서 이 타입 정의가 확정되기 전엔 둘 다 헛일할 위험이 있음 (각자 다른 모델로 짜면 통합할 때 재작업). **이 문서만 합의되면 그 뒤로는 서로 안 기다려도 됨.**

> 이 안은 확정이 아니라 초안이야. 5번에 채윤이가 확인/수정해줘야 하는 항목 정리해뒀어.

---

## 1. 설계 원칙 3개

**① SDK에 의존하지 않는 타입으로 정의한다**
Samsung Health SDK의 클래스를 그대로 화면까지 끌고 가면, SDK 버전이 바뀌거나 필드가 없을 때 앱 전체를 고쳐야 함. 그래서 SDK 결과는 `SamsungHealthSource` 안에서만 쓰고, **밖으로는 우리가 정한 타입으로만 내보냄.**

**② 실패를 예외로 던지지 않고 "결과 타입"으로 표현한다**
건강 데이터는 실패 경로가 많아 — 권한 거부 / Samsung Health 앱 미설치 / 해당 날짜에 데이터 없음 / SDK 미지원 항목. 이걸 전부 `try-catch`로 처리하면 화면 쪽에서 뭘 보여줘야 할지 알 수 없음. 그래서 `HealthResult`로 **실패 이유를 값으로 받아서** 화면에 그대로 안내 문구를 띄울 수 있게 함.

**③ 수면 단계 코드는 팀 공통 기준(0~4)을 그대로 쓴다** ★중요
우리 팀엔 이미 `평가코드.py`가 쓰는 공통 라벨이 있어:

| 코드 | 0 | 1 | 2 | 3 | 4 |
|---|---|---|---|---|---|
| 단계 | W(각성) | N1 | N2 | N3 | REM |

앱이 export하는 파일도 **이 코드를 쓰면 Galaxy Watch로 모은 데이터가 기존 평가 파이프라인에 바로 들어감.** 앱에서 따로 문자열("Light", "Deep" 등)로 내보내면 나중에 누군가 변환 코드를 또 짜야 함. 회의록의 *"본인 작업 결과물을 다음 단계 코드에서 바로 입력값으로 사용할 수 있는 형태로 만들 것"* 기준을 여기서 지키는 것.

---

## 2. 공통 데이터 모델 (Kotlin)

```kotlin
package app.sleepwell.health

// 모든 시각은 Long = epoch milliseconds (1970-01-01 UTC 부터의 밀리초)
// 사람이 읽는 형식으로는 export 시점에만 변환한다.

// ──────────────────────────────────────────────
// 기기 정보 — "이 데이터가 Watch 5에서 나온 게 맞나" 판단 근거
// ──────────────────────────────────────────────
data class DeviceInfo(
    val sourceName: String,      // 예: "Galaxy Watch5", "SM-R900"
    val deviceType: DeviceType,
    val rawSourceId: String?     // SDK가 준 원본 식별자. 필터링 근거를 버리지 않기 위해 보존
)

enum class DeviceType { WATCH, PHONE, MANUAL_INPUT, UNKNOWN }

// ──────────────────────────────────────────────
// 센서 1건씩
// ──────────────────────────────────────────────
data class HeartRateSample(
    val timeMs: Long,
    val bpm: Float,
    val device: DeviceInfo? = null
)

data class StepSample(
    val startMs: Long,           // 걸음은 "구간 합계"로 오는 경우가 많아 start/end를 둘 다 둠
    val endMs: Long,
    val count: Int,
    val device: DeviceInfo? = null
)

// ──────────────────────────────────────────────
// 수면 — 팀 공통 코드 0~4 고정
// ──────────────────────────────────────────────
enum class SleepStage(val code: Int, val label: String) {
    WAKE(0, "W"),
    N1(1, "N1"),
    N2(2, "N2"),
    N3(3, "N3"),
    REM(4, "REM"),
    UNKNOWN(-1, "UNKNOWN");      // 매핑 실패 시. export 시 평가에서 자동 제외됨

    companion object {
        fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: UNKNOWN
    }
}

/** 수면 단계가 유지된 한 구간 */
data class SleepStageSegment(
    val startMs: Long,
    val endMs: Long,
    val stage: SleepStage
)

/** 하룻밤 = 세션 1개 */
data class SleepSession(
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    val stages: List<SleepStageSegment>,   // 단계 정보가 없으면 빈 리스트 (수면/각성만 있는 경우)
    val device: DeviceInfo? = null
)

// ──────────────────────────────────────────────
// 조회 조건
// ──────────────────────────────────────────────
data class HealthQuery(
    val startMs: Long,
    val endMs: Long,
    val watchOnly: Boolean = true   // Galaxy Watch 소스만. 폰 자체 기록/수동입력 제외
)

// ──────────────────────────────────────────────
// 결과 타입 — 실패 이유를 값으로 표현
// ──────────────────────────────────────────────
sealed interface HealthResult<out T> {
    data class Success<T>(val data: T) : HealthResult<T>
    data object PermissionDenied : HealthResult<Nothing>
    data class Unavailable(val reason: String) : HealthResult<Nothing>  // 앱 미설치 / 항목 미지원
    data class Failure(val error: Throwable) : HealthResult<Nothing>
}
```

---

## 3. 공통 입구 (이 인터페이스만 맞추면 됨)

```kotlin
interface HealthDataSource {
    /** 화면에 "지금 어느 소스를 보고 있는지" 표시용. "SamsungHealth" 또는 "Fake" */
    val name: String

    /** 이 소스를 쓸 수 있는 환경인가 (Samsung Health 앱 설치·버전 등). Fake는 항상 true */
    suspend fun isAvailable(): Boolean

    suspend fun hasPermissions(): Boolean
    suspend fun requestPermissions(): HealthResult<Unit>

    suspend fun readHeartRate(q: HealthQuery): HealthResult<List<HeartRateSample>>
    suspend fun readSteps(q: HealthQuery): HealthResult<List<StepSample>>
    suspend fun readSleepSessions(q: HealthQuery): HealthResult<List<SleepSession>>
}
```

**함수 6개.** `suspend`인 이유는 SDK 조회가 비동기라서고, `FakeDataSource`는 그냥 즉시 return하면 돼서 문제없음.

- **채윤:** `class SamsungHealthSource(...) : HealthDataSource` 만들어서 SDK 결과를 위 타입으로 변환
- **소빈:** `class FakeDataSource : HealthDataSource` + 화면/그래프/export는 `HealthDataSource` 타입만 보고 작성

→ 나중에 통합할 때 **주입하는 구현체 한 줄만 바꾸면 실제 데이터로 돌아감.**

---

## 4. Export 형식 (④단계)

`meta.json` + CSV 4개를 한 폴더로 내보냄. 파일명 예: `sleepwell_export_<subjectId>_<yyyyMMdd_HHmm>/`

### `meta.json`
```json
{
  "subject_id": "S01",
  "exported_at": "2026-09-19T02:10:00+09:00",
  "timezone": "Asia/Seoul",
  "source": "fake",
  "app_version": "0.1.0",
  "device": { "source_name": "Galaxy Watch5", "device_type": "WATCH", "raw_source_id": "..." },
  "range": { "start": "2026-09-15T00:00:00+09:00", "end": "2026-09-18T00:00:00+09:00" }
}
```
`"source": "fake" | "samsung"` — **가상 데이터가 실제 데이터로 오인되는 사고를 막기 위해 반드시 기록.**

### `hr.csv`
`subject_id, time_iso, bpm, device_type, source_name`

### `steps.csv`
`subject_id, start_iso, end_iso, steps, device_type, source_name`

### `sleep_stage.csv`
`subject_id, session_id, start_iso, end_iso, stage_code, stage_label`

### `epoch_30s.csv` ★ 팀 파이프라인 직결 파일

**v1.2에서 `night` / `epoch_idx` 정의가 바뀌었어.** 아래가 확정본이야.
(채윤이 쪽 구현에는 영향 없음 — 이유는 이 절 마지막에.)

`subject_id, night, session_id, epoch_idx, stage_label, time_iso, hr_mean, steps`

| 열 | 정의 |
|---|---|
| `stage_label` | **정수 0~4** (기존 `평가코드.py`와 동일). 매핑 불가 구간은 `-1`, 평가에서 자동 제외 |
| `night` | **그 밤이 속한 날짜 `yyyyMMdd` 정수.** 정오~다음날 정오를 한 밤으로 봄 |
| `epoch_idx` | **`night` 날짜의 12:00(로컬)부터 센 30초 칸 번호.** `epoch_idx × 30 = 정오 기준 경과 초` |
| `session_id` | 같은 밤의 낮잠/밤잠을 구분하는 참고 열. **매칭 키 아님** (평가코드는 안 씀) |
| `hr_mean` | 해당 30초 구간 심박 평균 (없으면 **빈칸**. 0으로 안 채움 — 0은 "심박 0"이라는 거짓값) |
| `steps` | 해당 30초 구간 걸음 합계 (없으면 0) |

#### 왜 바꿨나

v1.1은 `night` = 조회 순번(1, 2, …), `epoch_idx` = 세션 시작부터 0. 둘 다 문제가 있었어.

1. **`night`이 조회 범위에 따라 변했다.** 9/17 밤이 3일 조회에서는 `night=2`, 7일 조회에서는 `night=5`가 됨. 평가코드가 `(subject_id, night, epoch_idx)`로 정답과 매칭하는데 키 값이 조회할 때마다 달라지면 매칭이 어긋나.
2. **`epoch_idx`가 세션마다 0에서 다시 시작했다.** 같은 날 낮잠과 밤잠이 둘 다 `epoch_idx=0`부터라서 `(night, epoch_idx)`가 충돌. 예측 1행이 정답 여러 행에 걸림.

이게 왜 심각한지는 실측했어. 8월 `평가코드.py`에서 정확히 같은 종류의 오정렬을 재현했을 때 — **F1(macro) −0.210 / Kappa −0.222, 그런데 오류 메시지는 한 줄도 안 나옴.** "잔차학습이 물리모델을 개선했다/안 했다"가 뒤집히는 폭이야. 눈으로는 못 잡는 오류라서 규격 자체를 안 깨지게 만들어야 해.

새 정의는 **같은 데이터를 언제 어떤 범위로 조회해도 같은 값**이 나오고, 낮잠·밤잠이 번호대가 갈려서(낮잠 260대 / 밤잠 1400~2200대) 구조적으로 충돌이 불가능해. 그리고 `epoch_idx × 30 = 경과 초`라는 성질이 Sleep-EDF 정답의 `onset_sec`과 동일해져서, 두 데이터를 같은 방식으로 다룰 수 있어.

#### 채윤이 쪽에 영향 없는 이유

`night` / `epoch_idx`는 **`SleepSession`에 없는 값**이야. 채윤이가 SDK에서 만들어주는 건 `sessionId / startMs / endMs / stages / device` 뿐이고, `night`과 `epoch_idx`는 내 `EpochAggregator`가 그 시각으로부터 **계산**해. 그래서 이 변경은 내 코드 안에서만 일어나고, 채윤이는 **바꿀 게 없어.** 이 문서에서 바뀐 건 export 파일 규격 설명뿐이야.

#### 검증

에뮬레이터에서 7일 조회로 export한 6,014행을 앱 밖에서 다시 검사했어 (`_작업\MVP_export검증.py`, 11항목 전부 통과).

- `epoch_idx × 30 == 정오 기준 경과 초` → 6,014행 전부 일치
- `(subject_id, night, epoch_idx)` 중복 → 0
- 자정을 넘긴 5,440행이 전날 `night`으로 유지됨
- 같은 `night`에 세션 2개인 밤 2개(9/13, 9/17)에서도 번호 충돌 0
  `night 20260913` → 낮잠(14:10~14:31) `epoch_idx` **260~301**, 밤잠(23:44~07:47) **1408~2373**.
  사이 1,106칸은 비어 있음 = 안 잔 시간이라 정상 (Sleep-EDF 정답 80개 파일과 같은 형태)

JSON export는 위 4개를 `{ "meta": {...}, "hr": [...], "steps": [...], "sleep_stage": [...], "epoch_30s": [...] }` 한 파일로.

> 왜 `epoch_30s.csv`를 따로 두나: 우리 팀 모든 데이터셋(Sleep-EDF·Walch·CMI)이 **30초 epoch**로 통일돼 있어. 앱 export도 같은 격자로 맞춰두면 Galaxy Watch 데이터가 별도 변환 없이 학습·평가에 들어감.

---

## 5. 채윤이 확인해줘야 하는 것 (여기에만 답해주면 됨)

SDK 실물을 붙여보는 건 채윤이 쪽이라, 아래는 내가 확정할 수 없어:

1. **`SleepStage` 5단계가 실제로 다 오나?** Samsung Health가 W/Light/Deep/REM 4구분으로만 줄 가능성이 있어. 그럼 N1/N2를 어떻게 다룰지 정해야 함 (내 제안: Light → `N2`로 매핑하고 `meta.json`에 매핑 규칙을 남긴다. **없는 정보를 만들어내지 않는 게 원칙.**)
2. **`DeviceInfo`로 Watch 5 필터링이 실제로 되나?** 회의록에도 "가능하면"이라고 되어 있어서, 안 되면 `watchOnly` 플래그는 무시하고 `rawSourceId`만 기록하는 걸로 후퇴하면 됨.
3. **심박이 raw로 오나, 가공된 요약으로 오나?** 회의록 7번에서 팀장님이 raw 확보 가능 여부를 중요하게 봤음. 요약만 온다면 `HeartRateSample`에 구간 필드가 필요할 수 있어.
4. **`readSteps`가 구간 합계로 오나, 시점값으로 오나?** 지금은 `start`/`end` 둘 다 두는 안전한 형태로 뒀어.
5. **패키지명·프로젝트 구조.** 프로젝트·Git 세팅은 채윤이 담당이니 `app.sleepwell.health`는 예시야 — 실제 패키지명 정해서 알려줘. **그리고 세팅 끝나면 리포지토리 주소만 먼저 던져줘. 내 파일은 그 위에 얹으면 돼.**
6. **SDK 사용 전제조건.** Samsung Health Data SDK는 Samsung Health 앱 버전·Android 버전 요건이 있고, 배포 단계에서 Samsung 쪽 파트너 승인이 필요한 것으로 알고 있어. 학술제 발표까지는 개발자 모드로 충분할 것 같은데, **막히는 지점이 생기면 바로 공유해줘.** 이번 주에 서윤·혜지도 같은 SDK를 조사하고 있으니 겹치는 부분은 물어보면 빠를 거야.

---

## 6. 이번 주 1차 구현 범위 (제안)

마감이 짧으니 회의록에서 "(가능하면)" / "(선택)" 표시된 건 **1차에서 제외**하자. 회의록 MVP 성공 기준도 *"실제 Watch 5 데이터가 정상적으로 읽히고 연구에 사용할 형태로 export되는 것"*까지야.

| | 1차에 넣을 것 | 2차로 미룰 것 |
|---|---|---|
| 채윤 | 프로젝트·Git 세팅, Samsung Health 연결 + 권한 요청(허용/거부), HR·Sleep·Sleep stage·Steps 조회 → 공통 타입 변환 | Watch 5 source 필터링, HRV·SpO2 |
| 소빈 | FakeDataSource, 조회 결과 화면(날짜 선택 + 항목별 요약), 수면 단계 그래프, CSV+JSON export | HR 추이·걸음 그래프, 수면 요약 카드 |

### 채윤 몫을 "워치 있어야 하는 것 / 없어도 되는 것"으로 쪼개자 (제안)

지금 채윤이 몫이 한 덩어리라서 **워치 구매가 늦어지면 전체가 멈춰.** 그런데 실제로는
워치 없이 끝낼 수 있는 부분이 대부분이야.

| | 내용 | 워치 필요? |
|---|---|---|
| **①-a** | 프로젝트·Git 세팅, Samsung Health 연결, 권한 요청 팝업, **거부됐을 때 처리**, **데이터가 0개일 때 앱이 안 죽는지** | **불필요** (갤럭시 폰만 있으면 됨) |
| **①-b** | HR·Sleep·**Sleep stage**·Steps 4종이 실제로 값이 채워져 나오는지 확인 | **필요** |

폰만 있어도 Samsung Health에 걸음 수는 이미 쌓여 있고, 권한 승인 흐름은 결과가
비어 있어도 똑같이 돌아. 워치가 있어야만 나오는 건 **수면 단계와 심박**이야.

그리고 ①-a에서 확인하는 "데이터 0개일 때"는 그냥 대기용 작업이 아니라 **실기기에서
실제로 자주 나오는 상황**이야 (시계 안 찬 날, 조회 범위에 수면이 없는 날). 여기서
앱이 죽으면 발표 시연에서 그대로 터져.

→ **①-a는 워치와 무관하게 지금 시작 가능.** 이렇게 나누면 워치 도착 지연이 일정
전체를 막지 않아.

---

## 7. 다음 액션

1. 채윤 → 5번 6개 항목 회신 (모르는 건 "모름"으로 남겨도 됨. 그게 리스크 목록이 됨)
2. 채윤 → 프로젝트 세팅 후 **리포지토리 주소 공유**
3. 소빈 → 회신 기다리는 동안 `FakeDataSource` + 화면 + export 착수 (이 문서의 타입 기준으로 진행)
4. 충돌/수정 필요하면 이 문서를 v2로 갱신하고 드라이브에 올림
