package com.runninggu.app.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

private const val STORE_NAME = "session"

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(STORE_NAME)

/**
 * 세션을 DataStore 에 남긴다. (SPEC §2.2 · NFR-11)
 *
 * **평문으로 저장한다.** `androidx.security-crypto` 로 감싸는 방안을 이슈 #77 에서 논의했고
 * "P0 는 지금 그대로" 로 정했다 — 그 라이브러리가 deprecated 이고, 앱을 뜯을 수 있는 기기는
 * 어차피 키스토어도 뜯긴다. 나중에 "왜 평문이지" 가 나오면 그 논의를 보면 된다.
 *
 * **읽기 실패는 로그아웃으로 다룬다.** 파일이 깨졌을 때 예외를 올려 앱을 못 켜게 하는 것보다
 * 게스트로 시작하는 편이 낫다(NFR-1). 토큰은 로그에 남기지 않는다(AGENTS 8장).
 */
class DataStoreSessionPersistence(context: Context) : SessionPersistence {

    // Application context 를 받는다. Activity 를 붙들면 화면 회전에서 새는 자리다
    private val store = context.applicationContext.sessionDataStore

    override suspend fun load(): PersistedSession? {
        val prefs = try {
            store.data
                .catch { cause ->
                    if (cause is IOException) {
                        Log.w(TAG, "세션 파일을 읽지 못해 게스트로 시작합니다", cause)
                        emit(emptyPreferences())
                    } else {
                        throw cause
                    }
                }
                .first()
        } catch (e: IOException) {
            Log.w(TAG, "세션 파일을 읽지 못해 게스트로 시작합니다", e)
            return null
        } catch (e: Exception) {
            // 직렬화 형식이 바뀌었거나 파일이 깨진 경우. 게스트로 시작하는 편이 낫다(NFR-1)
            Log.w(TAG, "저장된 세션을 해석하지 못해 게스트로 시작합니다", e)
            return null
        }

        val accessToken = prefs[KEY_ACCESS_TOKEN] ?: return null
        val refreshToken = prefs[KEY_REFRESH_TOKEN] ?: return null
        val nickname = prefs[KEY_NICKNAME] ?: return null
        // provider 가 모르는 값이면 통째로 버린다 — 계약이 바뀐 것이라 되살리면 더 헷갈린다
        val provider = prefs[KEY_LOGIN_PROVIDER]
            ?.let { name -> LoginProvider.entries.firstOrNull { it.name == name } }
            ?: return null

        return PersistedSession(
            tokens = AuthTokens(accessToken = accessToken, refreshToken = refreshToken),
            profile = SessionProfile(
                nickname = nickname,
                // 카카오 가입자는 이메일이 없을 수 있다. 없는 것과 못 읽은 것을 구분하지 않는다
                email = prefs[KEY_EMAIL],
                loginProvider = provider,
                marketingAgreed = prefs[KEY_MARKETING] ?: false,
            ),
        )
    }

    override suspend fun save(session: PersistedSession) {
        edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = session.tokens.accessToken
            prefs[KEY_REFRESH_TOKEN] = session.tokens.refreshToken
            prefs[KEY_NICKNAME] = session.profile.nickname
            prefs[KEY_LOGIN_PROVIDER] = session.profile.loginProvider.name
            prefs[KEY_MARKETING] = session.profile.marketingAgreed

            val email = session.profile.email
            // null 을 빈 문자열로 저장하면 "이메일 없음" 과 "빈 이메일" 이 같아진다
            if (email != null) prefs[KEY_EMAIL] = email else prefs.remove(KEY_EMAIL)
        }
    }

    /**
     * **실패를 삼키지 않는다.** 로그아웃은 지워졌는지가 곧 결과다 — 못 지웠는데 지운 척하면
     * 다음 실행에 이전 계정이 되살아난다(#89 리뷰).
     */
    override suspend fun clear() {
        store.edit { it.clear() }
    }

    /**
     * **저장** 실패는 삼킨다. 저장에 실패해도 이번 실행의 로그인은 그대로 살아 있고,
     * 다음에 앱을 켰을 때 다시 로그인하면 될 뿐이다 — 여기서 예외를 올리면 로그인
     * 성공 직후에 앱이 죽는다.
     */
    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            store.edit(block)
        } catch (e: IOException) {
            Log.w(TAG, "세션을 저장하지 못했습니다", e)
        }
    }

    private companion object {
        const val TAG = "SessionPersistence"

        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_NICKNAME = stringPreferencesKey("nickname")
        val KEY_EMAIL = stringPreferencesKey("email")
        val KEY_LOGIN_PROVIDER = stringPreferencesKey("login_provider")
        val KEY_MARKETING = booleanPreferencesKey("marketing_agreed")
    }
}
