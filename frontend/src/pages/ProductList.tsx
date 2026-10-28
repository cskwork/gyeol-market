import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useInfiniteQuery } from '@tanstack/react-query'
import { apiGetPage } from '@/lib/api'
import type { ProductListItem, SortOption, PageMeta } from '@/lib/types'
import ProductCard from '@/components/ProductCard/ProductCard'
import FilterBar from '@/components/FilterBar/FilterBar'
import { useCategoryTree, pouchStyle } from '@/lib/categories'
import styles from './ProductList.module.css'

const PAGE_SIZE = 20

interface CategoryPage {
  data: ProductListItem[]
  meta: PageMeta | undefined
}

function buildCategoryUrl(id: string, sort: SortOption, page: number): string {
  const qs = new URLSearchParams({
    sort,
    page: String(page),
    size: String(PAGE_SIZE),
  })
  return `/categories/${id}/products?${qs.toString()}`
}

export default function ProductList() {
  const { id } = useParams<{ id: string }>()
  const [sort, setSort] = useState<SortOption>('POPULAR')

  const categoryId = id ?? ''
  const { data: tree } = useCategoryTree()
  const category = tree?.categories.find((c) => String(c.id) === categoryId)
  const categoryName = category?.name

  const {
    data,
    isLoading,
    isError,
    error,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    refetch,
  } = useInfiniteQuery<CategoryPage, Error>({
    queryKey: ['category', 'products', categoryId, sort],
    queryFn: ({ pageParam, signal }) =>
      apiGetPage<ProductListItem[]>(
        buildCategoryUrl(categoryId, sort, pageParam as number),
        signal,
      ),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => {
      if (!lastPage.meta) return undefined
      const { page, size, total } = lastPage.meta
      return (page + 1) * size < total ? page + 1 : undefined
    },
    enabled: categoryId.length > 0,
  })

  const allProducts = data?.pages.flatMap((p) => p.data) ?? []
  const totalCount = data?.pages[0]?.meta?.total ?? 0
  const hasResults = allProducts.length > 0

  const handleSortChange = (next: SortOption) => {
    setSort(next)
  }

  const handleLoadMore = () => {
    fetchNextPage()
  }

  const handleRetry = () => {
    refetch()
  }

  return (
    <div className={styles.page}>
      {/* FilterBar sits at the top, sticky */}
      <FilterBar
        sort={sort}
        onSortChange={handleSortChange}
      />

      <div className="app-container">
        <div className={styles.inner}>
          {/* Category header */}
          <header className={styles.categoryHeader} style={pouchStyle(category?.slug)}>
            <div className={styles.categoryMeta}>
              <h1 className={styles.categoryTitle}>
                <span className={styles.categorySwatch} aria-hidden="true" />
                {categoryName ?? '카테고리'}
              </h1>
              {!isLoading && hasResults && (
                <p className={styles.totalCount}>
                  총{' '}
                  <strong className={styles.totalCountNum}>
                    {totalCount.toLocaleString('ko-KR')}
                  </strong>
                  개
                </p>
              )}
            </div>
          </header>

          {/* Loading skeleton */}
          {isLoading && (
            <ul
              className={styles.grid}
              aria-busy="true"
              aria-label="상품 로딩 중"
            >
              {Array.from({ length: 10 }, (_, i) => (
                <li key={i} aria-hidden="true">
                  <div className={`${styles.skeletonCard} skeleton-shimmer`} />
                </li>
              ))}
            </ul>
          )}

          {/* Error state */}
          {isError && !isLoading && (
            <div className="error-state" role="alert">
              <svg
                width="32"
                height="32"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.5"
                aria-hidden="true"
              >
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
              <p>상품을 불러오지 못했습니다.</p>
              <p className={styles.errorDetail}>{(error as Error).message}</p>
              <button
                type="button"
                className={styles.retryBtn}
                onClick={handleRetry}
              >
                다시 시도
              </button>
            </div>
          )}

          {/* Empty state */}
          {!isLoading && !isError && !hasResults && (
            <div className="empty-state">
              <svg
                width="48"
                height="48"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.2"
                aria-hidden="true"
              >
                <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
                <line x1="8" y1="21" x2="16" y2="21" />
                <line x1="12" y1="17" x2="12" y2="21" />
              </svg>
              <p className="empty-state__title">상품이 없습니다</p>
              <p>해당 카테고리에 등록된 상품이 없습니다.</p>
            </div>
          )}

          {/* Product grid */}
          {!isLoading && hasResults && (
            <ul
              className={styles.grid}
              aria-label={`${categoryName ?? '카테고리'} 상품 목록`}
            >
              {allProducts.map((product) => (
                <li key={product.productId}>
                  <ProductCard product={product} />
                </li>
              ))}
              {/* Inline skeleton while fetching next page */}
              {isFetchingNextPage &&
                Array.from({ length: 4 }, (_, i) => (
                  <li key={`skeleton-next-${i}`} aria-hidden="true">
                    <div className={`${styles.skeletonCard} skeleton-shimmer`} />
                  </li>
                ))}
            </ul>
          )}

          {/* Load more */}
          {!isLoading && !isError && hasNextPage && (
            <div className={styles.loadMoreWrap}>
              <button
                type="button"
                className={styles.loadMoreBtn}
                onClick={handleLoadMore}
                disabled={isFetchingNextPage}
                aria-label="상품 더 보기"
              >
                {isFetchingNextPage ? '로딩 중...' : '더 보기'}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
