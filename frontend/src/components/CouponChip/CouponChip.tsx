import styles from './CouponChip.module.css'

type CouponState = 'available' | 'downloaded' | 'expired'

interface CouponChipProps {
  label: string
  state?: CouponState
  onClick?: () => void
}

const ICON_MAP: Record<CouponState, string> = {
  available: '🎟',
  downloaded: '✓',
  expired: '✕',
}

const ARIA_MAP: Record<CouponState, string> = {
  available: '다운로드 가능',
  downloaded: '다운로드 완료',
  expired: '만료됨',
}

export default function CouponChip({ label, state = 'available', onClick }: CouponChipProps) {
  const isDisabled = state === 'downloaded' || state === 'expired'

  return (
    <button
      type="button"
      className={`${styles.chip} ${state === 'downloaded' ? styles.downloaded : ''} ${state === 'expired' ? styles.expired : ''}`}
      onClick={isDisabled ? undefined : onClick}
      disabled={isDisabled}
      aria-label={`쿠폰: ${label} — ${ARIA_MAP[state]}`}
      aria-pressed={state === 'downloaded' ? true : undefined}
    >
      <span className={styles.icon} aria-hidden="true">{ICON_MAP[state]}</span>
      {label}
    </button>
  )
}
