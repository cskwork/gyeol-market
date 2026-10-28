import { useRef } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { apiGetPage } from '@/lib/api'
import type { ProductListItem } from '@/lib/types'
import ProductCard from '@/components/ProductCard/ProductCard'
import { useCategoryTree, pouchName, pouchStyle, type CategoryNode } from '@/lib/categories'
import styles from './Home.module.css'

// ---- Rail section titles ---------------------------------------------------------

interface RailConfig {
  key: string
  title: string
  path: string
}

// Two rails over two different backend read paths: the ranking projection
// (rank_score) and the plain catalog list (newest first).
const RAILS: RailConfig[] = [
  { key: 'rankings', title: '지금 많이 보는 상품', path: '/products/rankings?page=0&size=10' },
  { key: 'latest', title: '새로 들어온 상품', path: '/products?sort=LATEST&page=0&size=10' },
]

// ---- Skeleton cards -------------------------------------------------------------

function SkeletonCard() {
  return (
    <div className={styles.skeletonCard} aria-hidden="true">
      <div className={`${styles.skeletonImg} skeleton-shimmer`} />
      <div className={styles.skeletonBody}>
        <div className={`${styles.skeletonLine} ${styles.skeletonLineSm} skeleton-shimmer`} />
        <div className={`${styles.skeletonLine} skeleton-shimmer`} />
        <div className={`${styles.skeletonLine} ${styles.skeletonLineMd} skeleton-shimmer`} />
      </div>
    </div>
  )
}

// ---- Product Rail ---------------------------------------------------------------

interface ProductRailProps {
  config: RailConfig
}

function ProductRail({ config }: ProductRailProps) {
  const railRef = useRef<HTMLDivElement>(null)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['products-rail', config.key],
    queryFn: ({ signal }) =>
      apiGetPage<ProductListItem[]>(config.path, signal),
    staleTime: 2 * 60 * 1000,
  })

  const products = data?.data ?? []

  return (
    <section className={styles.rail} aria-label={config.title}>
      <div className={styles.railHeader}>
        <h2 className={styles.railTitle}>
          {config.title}
        </h2>
        <Link to="/search" className={styles.railMore} aria-label={`${config.title} 전체 보기`}>
          전체 보기
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </Link>
      </div>

      {isError && (
        <p className="error-state" role="alert">
          상품을 불러오지 못했습니다.
        </p>
      )}

      {/* Mobile: horizontal scroll; Desktop: grid */}
      <div className={styles.railScroll} ref={railRef} aria-busy={isLoading}>
        <ul className={styles.railList}>
          {isLoading &&
            Array.from({ length: 6 }, (_, i) => (
              <li key={i} className={styles.railItem}>
                <SkeletonCard />
              </li>
            ))}
          {!isLoading && products.length === 0 && !isError && (
            <li className={styles.railEmpty}>
              <span className="empty-state">상품이 없습니다.</span>
            </li>
          )}
          {products.map((product) => (
            <li key={product.productId} className={styles.railItem}>
              <ProductCard product={product} />
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}

// ---- Category pouch rack -----------------------------------------------------------

function CategoryPouch({ category }: { category: CategoryNode }) {
  // One small query per pouch: total count + the top product's photo for the label window.
  const { data } = useQuery({
    queryKey: ['pouch', category.id],
    queryFn: ({ signal }) =>
      apiGetPage<ProductListItem[]>(`/categories/${category.id}/products?sort=POPULAR&page=0&size=1`, signal),
    staleTime: 5 * 60 * 1000,
  })
  const lead = data?.data[0]
  const total = data?.meta?.total

  return (
    <li className={styles.pouchItem} style={pouchStyle(category.slug)} data-pouch={pouchName(category.slug)}>
      <Link to={`/category/${category.id}`} className={styles.pouch}>
        <span className={styles.pouchName}>{category.name}</span>
        <span className={styles.pouchCount}>
          {total === undefined ? '상품 불러오는 중' : `상품 ${total}개`}
        </span>
        <span className={styles.pouchWindow} aria-hidden="true">
          {lead?.thumbnailUrl ? (
            <img src={lead.thumbnailUrl} alt="" className={styles.pouchImg} loading="eager" decoding="async" />
          ) : (
            <span className={`${styles.pouchImg} skeleton-shimmer`} />
          )}
        </span>
        <span className={styles.pouchFoot}>
          <span className={styles.pouchLeadWrap}>
            <span className={styles.pouchLead}>{lead ? lead.productName : '\u00a0'}</span>
            {lead && (
              <span className={styles.pouchPrice}>{lead.salePrice.toLocaleString('ko-KR')}원</span>
            )}
          </span>
          <span className={styles.pouchGo} aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
              <line x1="5" y1="12" x2="19" y2="12" />
              <polyline points="12 5 19 12 12 19" />
            </svg>
          </span>
        </span>
      </Link>
    </li>
  )
}

function PouchRack() {
  const { data, isLoading, isError } = useCategoryTree()
  const categories = data?.categories ?? []

  return (
    <section className={styles.rack} aria-labelledby="rack-title">
      <div className={styles.rackHead}>
        <h2 id="rack-title" className={styles.rackTitle}>카테고리별로 골라보세요</h2>
        <Link to="/search" className={styles.rackSearch}>전체 상품 보기</Link>
      </div>
      {isError && (
        <p className="error-state" role="alert">카테고리를 불러오지 못했습니다.</p>
      )}
      <ul className={styles.rackList} aria-busy={isLoading}>
        {isLoading &&
          Array.from({ length: 3 }, (_, i) => (
            <li key={i} className={styles.pouchItem}>
              <span className={`${styles.pouchSkeleton} skeleton-shimmer`} />
            </li>
          ))}
        {categories.map((category) => (
          <CategoryPouch key={category.id} category={category} />
        ))}
      </ul>
    </section>
  )
}

// ---- Page -----------------------------------------------------------------------

export default function Home() {
  return (
    <div className={styles.page}>
      <div className={styles.intro}>
        <h1>매일의 루틴, 나다운 한 가지.</h1>
        <p>루네픽, 뷰티·라이프스타일 데모 스토어.<br />스킨케어부터 메이크업, 헤어와 바디까지 둘러보세요.</p>
      </div>
      <PouchRack />

      <div className={styles.railsContainer}>
        {RAILS.map((rail) => (
          <ProductRail key={rail.key} config={rail} />
        ))}
      </div>
    </div>
  )
}
