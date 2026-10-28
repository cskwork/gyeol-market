import { useQueryClient } from '@tanstack/react-query'
import { clearTokens } from '@/lib/api'
import { resetDemoState } from '@/demo/mockApi'
import styles from './DemoNotice.module.css'

/** Always-visible strip in demo builds (VITE_DEMO=1): states plainly that no server is involved. */
export default function DemoNotice() {
  const queryClient = useQueryClient()
  const [confirmReset, setConfirmReset] = useState(false)

  const handleReset = () => {
    resetDemoState()
    clearTokens()
    queryClient.clear()
    window.location.assign(import.meta.env.BASE_URL)
  }

  return (
    <aside className={styles.notice} aria-label="데모 모드 안내">
      <p className={styles.text}>
        <strong className={styles.label}>데모 모드</strong>
        <span>샘플 쇼핑 · 실제 결제 없음<span className={styles.more}> · 장바구니와 주문은 이 브라우저에만 저장됩니다.</span></span>
      </p>
      <button type="button" className={styles.reset} onClick={() => setConfirmReset(true)} aria-label="데모 데이터 초기화">
        초기화
      </button>
      {confirmReset && (
        <div className={styles.confirm} role="group" aria-label="데모 데이터 초기화 확인">
          <p>장바구니·주문·계정이 삭제됩니다.</p>
          <button type="button" className={styles.reset} onClick={() => setConfirmReset(false)}>취소</button>
          <button type="button" className={styles.reset} onClick={handleReset}>초기화하기</button>
        </div>
      )}
    </aside>
  )
}
import { useState } from 'react'
