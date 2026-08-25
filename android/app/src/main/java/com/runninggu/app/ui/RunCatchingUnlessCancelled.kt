package com.runninggu.app.ui

import kotlinx.coroutines.CancellationException

/**
 * 취소를 실패로 접지 않는 [runCatching].
 *
 * `runCatching` 은 [CancellationException] 까지 잡는다. suspend 호출을 감싸면 **호출자가
 * 사라졌다는 신호가 "요청이 실패했다" 로 바뀐다.** 그러면 화면을 벗어나 코루틴이 취소된
 * 뒤에도 `onFailure` 가 돌아 오류 상태를 쓰고, 낙관적으로 바꾼 값을 되돌린다.
 *
 * **취소는 요청 결과가 아니다.** 그대로 올려보내야 구조적 동시성이 성립한다 — 부모가
 * 취소를 알아야 자식이 끝난 것으로 처리된다.
 *
 * `data` 레이어는 [com.runninggu.app.data.repository.RemoteFavoriteRepository] 가 이미
 * 같은 이유로 같은 규칙을 쓴다(#173 리뷰). 이 함수는 그 규칙을 **화면 쪽에도** 두는 것이다.
 */
internal inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
