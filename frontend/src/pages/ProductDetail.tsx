import { useState, useCallback, useEffect, useRef } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiGet, apiGetPage, ApiError, getAccessToken } from '@/lib/api'
import { addToCart } from '@/lib/cart'
import { addWishlist, removeWishlist, listWishlist } from '@/lib/wishlist'
import type { ProductDetail as ProductDetailType, PageMeta } from '@/lib/types'
import type { SelectedOption } from '@/components/QuantityOptionSelector/QuantityOptionSelector'
import ImageCarousel from '@/components/ImageCarousel/ImageCarousel'
import PriceDisplay from '@/components/PriceDisplay/PriceDisplay'
import RatingStars from '@/components/RatingStars/RatingStars'
import QuantityOptionSelector from '@/components/QuantityOptionSelector/QuantityOptionSelector'
import ReviewBlock from '@/components/ReviewBlock/ReviewBlock'
import styles from './ProductDetail.module.css'
import { IS_DEMO } from '@/lib/demo'

function formatKrw(n: number): string {
  return n.toLocaleString('ko-KR') + '원'
}

// Review shape returned by GET /api/products/{productId}/reviews
interface ProductReview {
  id: number
  productId: number
  rating: number
  title: string | null
  body: string
  imageUrls: string[] | null
  createdAt: string
}

// Masked author ID derived from review index (real API does not return member name in public endpoint)
function maskedAuthorId(reviewId: number): string {
  const suffix = String(reviewId).slice(-3).padStart(3, '0')
  return `고객***${suffix}`
}

// Format ISO date string to YYYY.MM.DD
function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString('ko-KR', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).replace(/\. /g, '.').replace(/\.$/, '')
  } catch {
    return iso.slice(0, 10)
  }
}

const REVIEW_PAGE_SIZE = 5

function ProductDetailView() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [selectedOptions, setSelectedOptions] = useState<SelectedOption[]>([])
  const [plainQty, setPlainQty] = useState(1)
  const [toastMsg, setToastMsg] = useState<string | null>(null)
  // Wishlist — optimistic local state + API mutations
  const [wishlistPending, setWishlistPending] = useState(false)
  const [isWishlisted, setIsWishlisted] = useState(false)
  // Review pagination — track pages loaded so far
  const [reviewPage, setReviewPage] = useState(0)
  // Accumulated reviews across pages
  const [accReviews, setAccReviews] = useState<ProductReview[]>([])
  const [accMeta, setAccMeta] = useState<PageMeta | undefined>(undefined)

  const { data: product, isLoading, isError, error } = useQuery({
    queryKey: ['product', id],
    queryFn: ({ signal }) => apiGet<ProductDetailType>(`/products/${id}`, signal),
    enabled: Boolean(id),
  })

  // Hydrate wishlist state: fetch page-1 of the member's wishlist (size=100) and
  // check if this product is present. Skipped when the user is not logged in.
  // Does not block render — isWishlisted stays false until the query resolves.
  const { data: wishlistPage } = useQuery({
    queryKey: ['wishlist'],
    queryFn: ({ signal }) => listWishlist({ page: 0, size: 100 }, signal),
    enabled: Boolean(id) && Boolean(getAccessToken()),
    staleTime: 30_000,
  })
  useEffect(() => {
    if (!wishlistPage || !id) return
    const productId = parseInt(id, 10)
    const found = wishlistPage.data.some((item) => item.productId === productId)
    setIsWishlisted(found)
  }, [wishlistPage, id])

  // Fetch a single page of reviews
  const { isFetching: reviewsFetching, isError: reviewsError, data: latestReviewPage } = useQuery({
    queryKey: ['product-reviews', id, reviewPage],
    queryFn: ({ signal }) =>
      apiGetPage<ProductReview[]>(
        `/products/${id}/reviews?sort=latest&page=${reviewPage}&size=${REVIEW_PAGE_SIZE}`,
        signal,
      ),
    enabled: Boolean(id),
  })

  // Track which page we last merged so we don't re-merge on re-renders
  const mergedPageRef = useRef<number>(-1)
  useEffect(() => {
    if (!latestReviewPage || mergedPageRef.current === reviewPage) return
    mergedPageRef.current = reviewPage
    const incoming = latestReviewPage.data ?? []
    setAccReviews((prev) => (reviewPage === 0 ? incoming : [...prev, ...incoming]))
    setAccMeta(latestReviewPage.meta)
  }, [latestReviewPage, reviewPage])

  const { mutateAsync: addToCartAsync, isPending: addingToCart } = useMutation({
    mutationFn: addToCart,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cart'] })
      showToast('장바구니에 담았습니다.')
    },
    onError: (err: unknown) => {
      if (err instanceof ApiError && err.status === 401) {
        navigate('/login', { state: { from: `/products/${id}` }, replace: true })
      } else {
        const msg = err instanceof Error ? err.message : '오류가 발생했습니다.'
        showToast(msg)
      }
    },
  })

  const showToast = (msg: string) => {
    setToastMsg(msg)
    setTimeout(() => setToastMsg(null), 2500)
  }

  // Returns true if the add-to-cart request(s) were dispatched successfully
  const dispatchAddToCart = useCallback(async (): Promise<boolean> => {
    if (!getAccessToken()) {
      navigate('/login', { state: { from: `/products/${id}` }, replace: true })
      return false
    }
    if (!product) return false

    if (product.options.length === 0) {
      try {
        await addToCartAsync({ productOptionId: product.productId, quantity: plainQty })
        return true
      } catch {
        return false
      }
    }

    if (selectedOptions.length === 0) {
      showToast('옵션을 선택해주세요.')
      return false
    }

    try {
      // Add all selected options; await the last one so navigate waits for completion
      for (const opt of selectedOptions) {
        await addToCartAsync({ productOptionId: opt.optionId, quantity: opt.quantity })
      }
      return true
    } catch {
      return false
    }
  }, [product, selectedOptions, plainQty, addToCartAsync, navigate, id])

  const handleAddToCart = useCallback(() => {
    dispatchAddToCart()
  }, [dispatchAddToCart])

  // BUG FIX: await cart mutation before navigating to /cart
  const handleBuyNow = useCallback(async () => {
    const ok = await dispatchAddToCart()
    if (ok) {
      navigate('/cart')
    }
  }, [dispatchAddToCart, navigate])

  // Wishlist toggle with optimistic UI + API persistence
  const handleWishlistToggle = useCallback(async () => {
    if (!getAccessToken()) {
      navigate('/login', { state: { from: `/products/${id}` }, replace: true })
      return
    }
    if (!product || wishlistPending) return

    const nextWishlisted = !isWishlisted
    setIsWishlisted(nextWishlisted)
    setWishlistPending(true)

    try {
      if (nextWishlisted) {
        await addWishlist(product.productId)
      } else {
        await removeWishlist(product.productId)
      }
      await queryClient.invalidateQueries({ queryKey: ['wishlist'] })
    } catch {
      // Revert optimistic update on failure
      setIsWishlisted(!nextWishlisted)
      showToast('찜 처리 중 오류가 발생했습니다.')
    } finally {
      setWishlistPending(false)
    }
  }, [product, isWishlisted, wishlistPending, navigate, id])

  const handleLoadMoreReviews = useCallback(() => {
    setReviewPage((p) => p + 1)
  }, [])

  // Price total: sum((salePrice + optionPrice) * qty) = salePrice * totalQty + sum(optionPrice * qty)
  const totalSelectedQty = selectedOptions.reduce((s, o) => s + o.quantity, 0)
  const totalOptionExtra = selectedOptions.reduce((s, o) => s + o.optionPrice * o.quantity, 0)
  const totalSelectedPrice =
    product ? product.salePrice * totalSelectedQty + totalOptionExtra : 0

  const hasMoreReviews =
    accMeta !== undefined
      ? accReviews.length < accMeta.total
      : false

  if (isLoading) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className={styles.skeleton} aria-busy="true" aria-label="상품 로딩 중" />
        </div>
      </div>
    )
  }

  if (isError || !product) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <p className={styles.errorMsg} role="alert">
            {isError ? (error as Error).message : '상품을 찾을 수 없습니다.'}
          </p>
          <div className="empty-state"><Link to="/search" className={styles.cartBtn}>상품 둘러보기</Link></div>
        </div>
      </div>
    )
  }

  const carouselImages = product.images
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((img) => ({ url: img.url, alt: product.productName }))

  const hasOptions = product.options.length > 0

  return (
    <div className={styles.page}>
      <div className="app-container">
        {/* Breadcrumb */}
        {product.categories.length > 0 && (
          <nav className={styles.breadcrumb} aria-label="카테고리 경로">
            <Link to="/" className={styles.breadcrumbLink}>홈</Link>
            {product.categories.map((cat, i) => (
              <span key={cat.categoryId} className={styles.breadcrumbItem}>
                <span className={styles.breadcrumbSep} aria-hidden="true">/</span>
                {i === product.categories.length - 1 ? (
                  <span className={styles.breadcrumbCurrent}>{cat.categoryName}</span>
                ) : (
                  <span className={styles.breadcrumbLink}>{cat.categoryName}</span>
                )}
              </span>
            ))}
          </nav>
        )}

        <div className={styles.layout}>
          {/* Left: carousel */}
          <div className={styles.imageSection}>
            <ImageCarousel images={carouselImages} />
          </div>

          {/* Right: product info */}
          <div className={styles.infoSection}>
            {product.brandName && (
              <p className={styles.brand}>{product.brandName}</p>
            )}

            <h1 className={styles.productName}>{product.productName}</h1>

            <div className={styles.ratingRow}>
              <RatingStars
                rating={product.rating}
                reviewCount={product.reviewCount}
                size="md"
              />
            </div>

            <div className={styles.priceRow}>
              <PriceDisplay
                salePrice={product.salePrice}
                originalPrice={product.originalPrice}
                discountRate={product.discountRate}
              />
            </div>

            <hr className={styles.divider} />

            {/* Option selector or plain qty */}
            {hasOptions ? (
              <QuantityOptionSelector
                options={product.options}
                unitPrice={product.salePrice}
                onChange={setSelectedOptions}
              />
            ) : (
              <div className={styles.plainQtyRow}>
                <span className={styles.plainQtyLabel}>수량</span>
                <div className={styles.stepper} role="group" aria-label="수량">
                  <button
                    type="button"
                    className={styles.stepBtn}
                    onClick={() => setPlainQty((q) => Math.max(1, q - 1))}
                    disabled={plainQty <= 1}
                    aria-label="수량 줄이기"
                  >
                    −
                  </button>
                  <span className={styles.stepCount}>{plainQty}</span>
                  <button
                    type="button"
                    className={styles.stepBtn}
                    onClick={() => setPlainQty((q) => q + 1)}
                    aria-label="수량 늘리기"
                  >
                    +
                  </button>
                </div>
              </div>
            )}

            {/* Price summary when options selected */}
            {hasOptions && selectedOptions.length > 0 && (
              <div className={styles.totalRow}>
                <span className={styles.totalLabel}>총 상품 금액</span>
                <span className={styles.totalPrice}>
                  {formatKrw(totalSelectedPrice)}
                </span>
              </div>
            )}

            {/* Wishlist button */}
            <button
              type="button"
              className={`${styles.wishlistBtn} ${isWishlisted ? styles.wishlistBtnActive : ''}`}
              onClick={handleWishlistToggle}
              disabled={wishlistPending}
              aria-label={isWishlisted ? '찜 취소' : '찜하기'}
              aria-pressed={isWishlisted}
            >
              <svg
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill={isWishlisted ? 'currentColor' : 'none'}
                stroke="currentColor"
                strokeWidth="2"
                aria-hidden="true"
              >
                <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
              </svg>
              {isWishlisted ? '찜 취소' : '찜하기'}
            </button>

            {/* Action buttons — desktop: inline; mobile: sticky bar */}
            <div className={styles.actionRow}>
              <button
                type="button"
                className={styles.cartBtn}
                onClick={handleAddToCart}
                disabled={addingToCart}
                aria-busy={addingToCart}
              >
                {addingToCart ? '담는 중...' : '장바구니 담기'}
              </button>
              <button
                type="button"
                className={styles.buyBtn}
                onClick={handleBuyNow}
                disabled={addingToCart}
              >
                바로구매
              </button>
            </div>
          </div>
        </div>

        {/* Description */}
        {product.description && (
          <section className={styles.descSection} aria-label="상품 설명">
            <h2 className={styles.descTitle}>상품 설명</h2>
            <div className={styles.descBody}>{product.description}</div>
          </section>
        )}

        {/* Reviews section */}
        <section className={styles.reviewsSection} aria-label="상품 리뷰">
          <div className={styles.reviewsHeader}>
            <h2 className={styles.reviewsTitle}>리뷰</h2>
            <div className={styles.reviewsSummary}>
              <RatingStars rating={product.rating} size="md" />
              <span className={styles.reviewsCount}>
                {product.reviewCount.toLocaleString('ko-KR')}개 리뷰
              </span>
            </div>
          </div>

          {accReviews.length === 0 && !reviewsFetching && !reviewsError && (
            <div className={styles.reviewsEmpty}>
              <p>아직 등록된 리뷰가 없습니다.</p>
              {IS_DEMO && <p className={styles.reviewsEmptyHint}>데모에는 작성된 리뷰가 없습니다.</p>}
            </div>
          )}

          {reviewsError && (
            <p className={styles.reviewsErrorMsg} role="alert">
              리뷰를 불러오지 못했습니다.
            </p>
          )}

          {accReviews.length > 0 && (
            <div className={styles.reviewsList}>
              {accReviews.map((review) => (
                <ReviewBlock
                  key={review.id}
                  reviewId={review.id}
                  authorMaskedId={maskedAuthorId(review.id)}
                  rating={review.rating}
                  date={formatDate(review.createdAt)}
                  body={review.body}
                  photoUrls={review.imageUrls ?? []}
                  helpfulCount={0}
                />
              ))}
            </div>
          )}

          {hasMoreReviews && (
            <button
              type="button"
              className={styles.loadMoreBtn}
              onClick={handleLoadMoreReviews}
              disabled={reviewsFetching}
              aria-busy={reviewsFetching}
            >
              {reviewsFetching ? '불러오는 중...' : '리뷰 더 보기'}
            </button>
          )}

          {reviewsFetching && accReviews.length === 0 && (
            <div
              className={styles.reviewsSkeleton}
              aria-busy="true"
              aria-label="리뷰 로딩 중"
            />
          )}
        </section>
      </div>

      {/* Mobile sticky buy bar — z-index must exceed any bottom tab bar */}
      <div className={styles.stickyBar} aria-label="구매 버튼">
        <button
          type="button"
          className={styles.stickyCartBtn}
          onClick={handleAddToCart}
          disabled={addingToCart}
        >
          장바구니 담기
        </button>
        <button
          type="button"
          className={styles.stickyBuyBtn}
          onClick={handleBuyNow}
          disabled={addingToCart}
        >
          바로구매
        </button>
      </div>

      {/* Toast notification */}
      {toastMsg && (
        <div className={styles.toast} role="status" aria-live="polite">
          {toastMsg}
        </div>
      )}
    </div>
  )
}

export default function ProductDetail() {
  const { id } = useParams()
  // Reset option quantities, wishlist and review pagination on another product.
  return <ProductDetailView key={id} />
}
