import { useState, useEffect } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { ProductListItem } from '@/lib/types'
import { getAccessToken } from '@/lib/api'
import { addWishlist, removeWishlist, listWishlist } from '@/lib/wishlist'
import RatingStars from '@/components/RatingStars/RatingStars'
import PriceDisplay from '@/components/PriceDisplay/PriceDisplay'
import styles from './ProductCard.module.css'

interface ProductCardProps {
  product: ProductListItem
  /** Initial wishlist state when the caller already knows it. */
  wished?: boolean
}

export default function ProductCard({ product, wished: initialWished = false }: ProductCardProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const [wished, setWished] = useState(initialWished)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const { data: wishlist } = useQuery({
    queryKey: ['wishlist'],
    queryFn: ({ signal }) => listWishlist({ page: 0, size: 100 }, signal),
    enabled: Boolean(getAccessToken()),
  })
  useEffect(() => {
    setWished(wishlist ? wishlist.data.some((item) => item.productId === product.productId) : initialWished)
  }, [wishlist, product.productId, initialWished])

  // Persists to /api/me/wishlist (optimistic, reverted on failure). Anonymous
  // visitors are sent to login and brought back here afterwards.
  const handleWishlistClick = async () => {
    if (!getAccessToken()) {
      navigate('/login', { state: { from: location.pathname + location.search } })
      return
    }
    if (pending) return
    const next = !wished
    setWished(next)
    setPending(true)
    setError(null)
    try {
      await (next ? addWishlist(product.productId) : removeWishlist(product.productId))
      await queryClient.invalidateQueries({ queryKey: ['wishlist'] })
    } catch {
      setWished(!next)
      setError('찜을 저장하지 못했습니다. 다시 눌러주세요.')
    } finally {
      setPending(false)
    }
  }

  return (
    <article className={styles.card}>
      <Link
        to={`/products/${product.productId}`}
        className={styles.link}
        aria-label={`${product.brandName ? product.brandName + ' ' : ''}${product.productName} 상품 상세 보기`}
      >
        <div className={styles.imageWrap}>
          {product.thumbnailUrl ? (
            <img
              src={product.thumbnailUrl}
              alt={product.productName}
              className={styles.image}
              loading="lazy"
              decoding="async"
            />
          ) : (
            <div className={styles.imagePlaceholder} aria-hidden="true">
              <svg width="40" height="40" viewBox="0 0 24 24" fill="none">
                <rect width="24" height="24" rx="4" fill="#e5e5e5" />
                <path d="M8 16l3-4 2 3 2-2 3 3H8z" fill="#bfbfbf" />
                <circle cx="15" cy="9" r="1.5" fill="#bfbfbf" />
              </svg>
            </div>
          )}

          {product.discountRate >= 30 && (
            <div className={styles.badges}>
              <span className={`${styles.badge} ${styles.badgeBest}`}>특가</span>
            </div>
          )}
        </div>

        <div className={styles.info}>
          {product.brandName && <p className={styles.brand}>{product.brandName}</p>}
          <p className={styles.name}>{product.productName}</p>
          <div className={styles.priceRow}>
            <PriceDisplay
              salePrice={product.salePrice}
              originalPrice={product.originalPrice}
              discountRate={product.discountRate}
            />
          </div>
          {(product.reviewCount > 0 || product.rating > 0) && (
            <div className={styles.ratingRow}>
              <RatingStars rating={product.rating} reviewCount={product.reviewCount} size="sm" />
            </div>
          )}
        </div>
      </Link>

      {/* Sibling of the link, not nested: a button inside <a> is invalid and unreachable by keyboard. */}
      <button
        type="button"
        className={`${styles.wishlistBtn} ${wished ? styles.wished : ''}`}
        onClick={handleWishlistClick}
        disabled={pending}
        aria-label={wished ? `${product.productName} 찜 해제` : `${product.productName} 찜하기`}
        aria-pressed={wished}
        aria-busy={pending}
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill={wished ? 'currentColor' : 'none'}
          stroke="currentColor"
          strokeWidth="2"
          aria-hidden="true"
        >
          <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
        </svg>
      </button>
      {error && <p className={styles.error} role="alert">{error}</p>}
    </article>
  )
}
