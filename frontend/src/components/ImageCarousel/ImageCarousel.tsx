import { useState, useCallback } from 'react'
import styles from './ImageCarousel.module.css'

interface CarouselImage {
  url: string
  alt?: string
}

interface ImageCarouselProps {
  images: CarouselImage[]
}

export default function ImageCarousel({ images }: ImageCarouselProps) {
  const [current, setCurrent] = useState(0)

  const prev = useCallback(() => {
    setCurrent((i) => (i === 0 ? images.length - 1 : i - 1))
  }, [images.length])

  const next = useCallback(() => {
    setCurrent((i) => (i === images.length - 1 ? 0 : i + 1))
  }, [images.length])

  if (images.length === 0) return null

  const active = images[current]

  return (
    <div className={styles.root}>
      {/* Main image */}
      <div
        className={styles.main}
        role="region"
        aria-label="상품 이미지"
        aria-roledescription="carousel"
      >
        <img
          key={current}
          src={active.url}
          alt={active.alt ?? `상품 이미지 ${current + 1}`}
          className={styles.mainImage}
          loading="lazy"
          decoding="async"
        />

        {images.length > 1 && (
          <>
            <button
              type="button"
              className={`${styles.arrow} ${styles.arrowPrev}`}
              onClick={prev}
              aria-label="이전 이미지"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" aria-hidden="true">
                <polyline points="15 18 9 12 15 6" />
              </svg>
            </button>
            <button
              type="button"
              className={`${styles.arrow} ${styles.arrowNext}`}
              onClick={next}
              aria-label="다음 이미지"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" aria-hidden="true">
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </button>
          </>
        )}
      </div>

      {/* Mobile: dots */}
      {images.length > 1 && (
        <div className={styles.dots} role="tablist" aria-label="이미지 선택">
          {images.map((_, i) => (
            <button
              key={i}
              type="button"
              role="tab"
              className={`${styles.dot} ${i === current ? styles.active : ''}`}
              onClick={() => setCurrent(i)}
              aria-label={`이미지 ${i + 1}`}
              aria-selected={i === current}
            />
          ))}
        </div>
      )}

      {/* Desktop: thumbnail strip */}
      {images.length > 1 && (
        <div className={styles.thumbs} role="tablist" aria-label="이미지 썸네일">
          {images.map((img, i) => (
            <button
              key={i}
              type="button"
              role="tab"
              className={`${styles.thumb} ${i === current ? styles.active : ''}`}
              onClick={() => setCurrent(i)}
              aria-label={img.alt ?? `이미지 ${i + 1}`}
              aria-selected={i === current}
            >
              <img
                src={img.url}
                alt=""
                className={styles.thumbImage}
                loading="lazy"
                decoding="async"
              />
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
