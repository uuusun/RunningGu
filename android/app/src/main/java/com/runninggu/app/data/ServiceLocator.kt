package com.runninggu.app.data

import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.remote.ApiClient
import com.runninggu.app.data.remote.ContestApi
import com.runninggu.app.data.remote.CourseApi
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.RemoteContestRepository
import com.runninggu.app.data.repository.RemoteCourseRepository
import retrofit2.Retrofit
import retrofit2.create

/**
 * 앱이 서버와 이야기하는 **단 하나의 진입점**. (SPEC §9.3 · AP-14)
 *
 * 지금까지 `ApiClient.create()` 를 부르는 곳이 아예 없어서, Retrofit 클라이언트가 만들어진
 * 적이 없었다 — 화면이 전부 스텁을 보고 있었기 때문이다. 서버 엔드포인트가 서면 화면의
 * 저장소만 여기 것으로 바꾸면 된다(AGENTS 4장).
 *
 * **토큰은 여기서 붙는다.** [SessionStore.tokens] 를 `TokenProvider` 로 물려 두었으므로,
 * 로그인 뒤에는 모든 요청에 `Authorization: Bearer` 가 자동으로 실린다(§0-2). 토큰이 없으면
 * 헤더를 안 붙이므로 공개 API 는 게스트로 그대로 동작한다.
 *
 * DI 라이브러리를 쓰지 않는 이유는 새 의존성을 늘리지 않기 위해서다(AGENTS 7장). 화면이
 * 늘어 손이 가면 그때 팀에 물어 도입한다.
 */
object ServiceLocator {

    /**
     * 프로세스에 하나만 둔다 — OkHttp 커넥션 풀과 스레드를 재사용해야 한다.
     *
     * 토큰을 **호출 시점에** 읽는다. 인스턴스를 만들 때 값을 박아 두면 로그인 이후에도
     * 게스트로 나간다.
     */
    private val retrofit: Retrofit by lazy {
        ApiClient.create(tokenProvider = { SessionStore.tokens?.accessToken })
    }

    val contestApi: ContestApi by lazy { retrofit.create() }
    val courseApi: CourseApi by lazy { retrofit.create() }

    /** 서버 구현. 화면은 인터페이스만 보므로 스텁과 바꿔 끼울 수 있다. */
    val contestRepository: ContestRepository by lazy { RemoteContestRepository(contestApi) }
    val courseRepository: CourseRepository by lazy { RemoteCourseRepository(courseApi) }
}
