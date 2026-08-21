package com.runninggu.app.data.local

/**
 * 프로세스 밖에 남겨 두는 세션. (SPEC §2.2 · AP-14)
 *
 * 토큰과 프로필을 **함께** 다룬다 — 토큰만 남으면 로그인은 됐는데 누구인지 모르는 상태가
 * 되고, 프로필만 남으면 화면은 로그인처럼 보이는데 요청이 전부 401 로 떨어진다.
 */
data class PersistedSession(
    val tokens: AuthTokens,
    val profile: SessionProfile,
)

/**
 * 세션 영속 창구. (SPEC §2.2)
 *
 * [SessionStore] 는 이 인터페이스만 본다 — 저장 방식을 바꾸거나 테스트에서 가짜로
 * 갈아끼워도 세션 로직이 안 흔들린다.
 *
 * 구현은 [DataStoreSessionPersistence] 다.
 */
interface SessionPersistence {

    /** 앱 시작 때 한 번. 저장된 게 없거나 읽다 실패하면 null 이다(게스트로 시작). */
    suspend fun load(): PersistedSession?

    /** 통째로 덮어쓴다. 부분 갱신을 만들지 않는 이유는 [PersistedSession] 주석과 같다. */
    suspend fun save(session: PersistedSession)

    /** 로그아웃·탈퇴. 토큰이 디스크에 남지 않게 지운다. */
    suspend fun clear()
}

/** 아무것도 남기지 않는 구현. 테스트와 미리보기용이다. */
object NoSessionPersistence : SessionPersistence {
    override suspend fun load(): PersistedSession? = null
    override suspend fun save(session: PersistedSession) = Unit
    override suspend fun clear() = Unit
}
