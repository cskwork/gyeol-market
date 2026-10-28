import styles from './RatingStars.module.css'

interface RatingStarsProps {
  rating: number
  reviewCount?: number
  size?: 'sm' | 'md'
}

const STAR_COUNT = 5

function StarIcon({ className }: { className?: string }) {
  return (
    <svg
      className={`${styles.starSvg} ${className ?? ''}`}
      width="1em"
      height="1em"
      viewBox="0 0 16 16"
      aria-hidden="true"
    >
      <path
        fill="currentColor"
        d="M8 1l2.163 4.381L15 6.116l-3.5 3.41.826 4.815L8 12l-4.326 2.341.826-4.815L1 6.116l4.837-.735L8 1z"
      />
    </svg>
  )
}

export default function RatingStars({ rating, reviewCount, size = 'md' }: RatingStarsProps) {
  const clampedRating = Math.max(0, Math.min(STAR_COUNT, rating))
  const label =
    reviewCount !== undefined
      ? `평점 ${clampedRating.toFixed(1)}, 리뷰 ${reviewCount.toLocaleString('ko-KR')}개`
      : `평점 ${clampedRating.toFixed(1)}`

  return (
    <span
      className={`${styles.root} ${size === 'sm' ? styles.sm : styles.md}`}
      role="img"
      aria-label={label}
    >
      <span className={styles.stars} aria-hidden="true">
        {Array.from({ length: STAR_COUNT }, (_, i) => {
          const fillPct = Math.max(0, Math.min(1, clampedRating - i)) * 100
          return (
            <span key={i} className={styles.starWrapper}>
              <StarIcon />
              {fillPct > 0 && (
                <span
                  className={styles.starFill}
                  style={{ width: `${fillPct}%` }}
                >
                  <StarIcon />
                </span>
              )}
            </span>
          )
        })}
      </span>
      {reviewCount !== undefined && (
        <span className={styles.count}>({reviewCount.toLocaleString('ko-KR')})</span>
      )}
    </span>
  )
}
