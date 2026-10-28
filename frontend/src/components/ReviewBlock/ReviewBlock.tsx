import { useState } from 'react'
import RatingStars from '@/components/RatingStars/RatingStars'
import styles from './ReviewBlock.module.css'

export interface ReviewBlockProps {
  reviewId: number
  authorMaskedId: string
  rating: number
  date: string
  body: string
  photoUrls?: string[]
  helpfulCount: number
}

export default function ReviewBlock({
  reviewId,
  authorMaskedId,
  rating,
  date,
  body,
  photoUrls = [],
  helpfulCount,
}: ReviewBlockProps) {
  const [expanded, setExpanded] = useState(false)
  const [helpful, setHelpful] = useState(false)
  const [localHelpfulCount, setLocalHelpfulCount] = useState(helpfulCount)
  const needsExpand = body.length > 200

  const handleHelpful = () => {
    if (helpful) {
      setHelpful(false)
      setLocalHelpfulCount((n) => n - 1)
    } else {
      setHelpful(true)
      setLocalHelpfulCount((n) => n + 1)
    }
  }

  return (
    <article className={styles.root} aria-label={`${authorMaskedId}님의 리뷰`}>
      <div className={styles.header}>
        <div className={styles.meta}>
          <span className={styles.author}>{authorMaskedId}</span>
          <div className={styles.dateRow}>
            <RatingStars rating={rating} size="sm" />
            <time className={styles.date} dateTime={date}>{date}</time>
          </div>
        </div>
      </div>

      <p className={`${styles.body} ${expanded ? styles.expanded : ''}`} key={`body-${reviewId}`}>
        {body}
      </p>

      {needsExpand && !expanded && (
        <button
          type="button"
          className={styles.expandBtn}
          onClick={() => setExpanded(true)}
          aria-expanded={false}
        >
          더 보기
        </button>
      )}

      {photoUrls.length > 0 && (
        <div className={styles.photos} aria-label="리뷰 사진">
          {photoUrls.map((url, i) => (
            <img
              key={i}
              src={url}
              alt={`리뷰 사진 ${i + 1}`}
              className={styles.photo}
              loading="lazy"
              decoding="async"
            />
          ))}
        </div>
      )}

      <div className={styles.helpful}>
        <button
          type="button"
          className={`${styles.helpfulBtn} ${helpful ? styles.active : ''}`}
          onClick={handleHelpful}
          aria-pressed={helpful}
          aria-label={`도움돼요 ${localHelpfulCount}개`}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z" />
            <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3" />
          </svg>
          도움돼요
          <span className={styles.helpfulCount}>{localHelpfulCount}</span>
        </button>
      </div>
    </article>
  )
}
