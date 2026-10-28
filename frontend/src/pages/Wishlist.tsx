import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { listWishlist, removeWishlist } from '@/lib/wishlist'
import { useRequireAuth } from '@/lib/useRequireAuth'
import type { WishlistItem, ProductListItem, PageMeta } from '@/lib/types'
import ProductCard from '@/components/ProductCard/ProductCard'
import styles from './Wishlist.module.css'

interface WishlistQueryData {
  data: WishlistItem[]
  meta: PageMeta | undefined
}

/** Map WishlistItem to the ProductListItem shape ProductCard requires. */
function toProductListItem(item: WishlistItem): ProductListItem {
  return {
    productId: item.productId,
    brandName: item.brandName,
    productName: item.productName,
    salePrice: item.salePrice,
    originalPrice: item.originalPrice,
    discountRate: item.discountRate,
    thumbnailUrl: item.thumbnailUrl,
    rating: 0,
    reviewCount: 0,
  }
}

function GridSkeleton() {
  return (
    <div className={styles.grid} aria-busy="true" aria-label="찜 목록 로딩 중">
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className={styles.cardSkeleton}>
          <div className={styles.skeletonImage} />
          <div className={styles.skeletonInfo}>
            <div className={styles.skeletonLine} style={{ width: '60%' }} />
            <div className={styles.skeletonLine} style={{ width: '90%' }} />
            <div className={styles.skeletonLine} style={{ width: '45%' }} />
          </div>
        </div>
      ))}
    </div>
  )
}

interface WishlistCardWrapperProps {
  item: WishlistItem
  onRemove: (productId: number) => void
  isRemoving: boolean
}

function WishlistCardWrapper({ item, onRemove, isRemoving }: WishlistCardWrapperProps) {
  const product = toProductListItem(item)

  return (
    <div className={`${styles.cardWrapper} ${isRemoving ? styles.removing : ''}`}>
      <ProductCard product={product} wished />
      <button
        type="button"
        className={styles.removeBtn}
        onClick={() => onRemove(item.productId)}
        disabled={isRemoving}
        aria-label={`${item.productName} 찜 해제`}
        aria-busy={isRemoving}
      >
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          aria-hidden="true"
        >
          <line x1="18" y1="6" x2="6" y2="18" />
          <line x1="6" y1="6" x2="18" y2="18" />
        </svg>
        <span className="visually-hidden">찜 해제</span>
      </button>
    </div>
  )
}

export default function Wishlist() {
  useRequireAuth()

  const queryClient = useQueryClient()

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['wishlist'],
    queryFn: ({ signal }) => listWishlist({ page: 0, size: 100 }, signal),
  })

  const removeMutation = useMutation({
    mutationFn: (productId: number) => removeWishlist(productId),
    onMutate: async (productId: number) => {
      await queryClient.cancelQueries({ queryKey: ['wishlist'] })
      const previous = queryClient.getQueryData<WishlistQueryData>(['wishlist'])
      queryClient.setQueryData<WishlistQueryData>(['wishlist'], (old) => {
        if (!old) return old
        return {
          ...old,
          data: old.data.filter((item) => item.productId !== productId),
        }
      })
      return { previous }
    },
    onError: (_err, _productId, context) => {
      if (context?.previous !== undefined) {
        queryClient.setQueryData<WishlistQueryData>(['wishlist'], context.previous)
      }
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['wishlist'] })
    },
  })

  const items = data?.data ?? []

  const handleRemove = (productId: number) => {
    removeMutation.mutate(productId)
  }

  if (isLoading) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <h1 className={styles.heading}>찜 목록</h1>
          <GridSkeleton />
        </div>
      </div>
    )
  }

  if (isError) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <h1 className={styles.heading}>찜 목록</h1>
          <div className="error-state" role="alert">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <p>찜 목록을 불러오지 못했습니다.</p>
            {error instanceof Error && (
              <p style={{ fontSize: 12, color: 'var(--grey-500)' }}>{error.message}</p>
            )}
            <button
              type="button"
              className={styles.retryBtn}
              onClick={() => void refetch()}
            >
              다시 시도
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className={styles.page}>
      <div className="app-container">
        <header className={styles.pageHeader}>
          <h1 className={styles.heading}>찜 목록</h1>
          {items.length > 0 && (
            <span className={styles.itemCount} aria-live="polite">
              <strong>{items.length}</strong>개 상품
            </span>
          )}
        </header>

        {items.length === 0 ? (
          <div className="empty-state" role="status">
            <svg
              width="56"
              height="56"
              viewBox="0 0 24 24"
              fill="none"
              stroke="var(--grey-300)"
              strokeWidth="1.2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
            </svg>
            <p className="empty-state__title">찜한 상품이 없습니다</p>
            <p>마음에 드는 상품을 찜해보세요!</p>
            <Link to="/" className={styles.browseLink}>
              상품 둘러보기
            </Link>
          </div>
        ) : (
          <div className={styles.grid} role="list" aria-label="찜한 상품 목록">
            {items.map((item) => (
              <div key={item.wishlistId} role="listitem">
                <WishlistCardWrapper
                  item={item}
                  onRemove={handleRemove}
                  isRemoving={
                    removeMutation.isPending &&
                    removeMutation.variables === item.productId
                  }
                />
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
