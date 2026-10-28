/**
 * Component gallery for visual QA.
 * Route: /dev
 * No Storybook dependency — renders every component with sample props.
 */
import { useState } from 'react'
import ProductCard from '@/components/ProductCard/ProductCard'
import RatingStars from '@/components/RatingStars/RatingStars'
import PriceDisplay from '@/components/PriceDisplay/PriceDisplay'
import CouponChip from '@/components/CouponChip/CouponChip'
import ReviewBlock from '@/components/ReviewBlock/ReviewBlock'
import ImageCarousel from '@/components/ImageCarousel/ImageCarousel'
import FilterBar from '@/components/FilterBar/FilterBar'
import type { AppliedFilter } from '@/components/FilterBar/FilterBar'
import QuantityOptionSelector from '@/components/QuantityOptionSelector/QuantityOptionSelector'
import type { SelectedOption } from '@/components/QuantityOptionSelector/QuantityOptionSelector'
import type { SortOption, ProductListItem, ProductOptionSummary } from '@/lib/types'
import styles from './Dev.module.css'

// --- Sample data ---

const SAMPLE_PRODUCT: ProductListItem = {
  productId: 1,
  brandName: '새봄담',
  productName: '히알루론 96 수분 부스팅 에센스 (히알루론산 복합체 96%)',
  salePrice: 18900,
  originalPrice: 25000,
  discountRate: 24,
  thumbnailUrl: '/images/demo/product-1.webp',
  rating: 4.7,
  reviewCount: 12543,
}

const SAMPLE_PRODUCT_NO_DISCOUNT: ProductListItem = {
  ...SAMPLE_PRODUCT,
  productId: 2,
  brandName: '그린마루',
  productName: '티트리 카밍 트러블 세럼',
  salePrice: 28000,
  originalPrice: 28000,
  discountRate: 0,
  thumbnailUrl: '/images/demo/product-2.webp',
  rating: 4.2,
  reviewCount: 3201,
}

const SAMPLE_PRODUCT_NO_IMAGE: ProductListItem = {
  ...SAMPLE_PRODUCT,
  productId: 3,
  brandName: null,
  productName: '이미지 없는 상품 테스트 (긴 제목 클램프 확인용)',
  salePrice: 9900,
  originalPrice: 15000,
  discountRate: 34,
  thumbnailUrl: null,
  rating: 0,
  reviewCount: 0,
}

const SAMPLE_IMAGES = [
  { url: '/images/demo/product-1.webp', alt: '상품 정면' },
  { url: '/images/demo/product-2.webp', alt: '상품 측면' },
  { url: '/images/demo/product-3.webp', alt: '상품 후면' },
]

const SAMPLE_OPTIONS: ProductOptionSummary[] = [
  { optionId: 1, optionName: '50ml (기본)', optionPrice: 0, status: 'ON_SALE', availableQuantity: 10 },
  { optionId: 2, optionName: '100ml (+5,000원)', optionPrice: 5000, status: 'ON_SALE', availableQuantity: 3 },
  { optionId: 3, optionName: '품절 옵션', optionPrice: 0, status: 'SOLD_OUT', availableQuantity: 0 },
]

const SAMPLE_FILTERS: AppliedFilter[] = [
  { id: 'brand-saebomdam', label: '새봄담' },
  { id: 'price-under-20k', label: '2만원 이하' },
]

// --- Section wrapper ---

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>{title}</h2>
      <div className={styles.sectionBody}>{children}</div>
    </section>
  )
}

// --- Gallery page ---

export default function Dev() {
  const [sort, setSort] = useState<SortOption>('POPULAR')
  const [filters, setFilters] = useState<AppliedFilter[]>(SAMPLE_FILTERS)
  const [selectedOptions, setSelectedOptions] = useState<SelectedOption[]>([])

  const removeFilter = (id: string) => setFilters((f) => f.filter((x) => x.id !== id))

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.pageTitle}>Component Gallery</h1>
        <p className={styles.pageSubtitle}>Visual QA — all components with sample props</p>
      </header>

      {/* 1. ProductCard */}
      <Section title="1. ProductCard">
        <div className={styles.cardGrid}>
          <ProductCard product={SAMPLE_PRODUCT} />
          <ProductCard product={SAMPLE_PRODUCT_NO_DISCOUNT} />
          <ProductCard product={SAMPLE_PRODUCT_NO_IMAGE} />
        </div>
      </Section>

      {/* 2. RatingStars */}
      <Section title="2. RatingStars">
        <div className={styles.row}>
          <div>
            <p className={styles.label}>size=md, 4.7, with count</p>
            <RatingStars rating={4.7} reviewCount={12543} size="md" />
          </div>
          <div>
            <p className={styles.label}>size=sm, 3.5, no count</p>
            <RatingStars rating={3.5} size="sm" />
          </div>
          <div>
            <p className={styles.label}>0 stars</p>
            <RatingStars rating={0} reviewCount={0} />
          </div>
          <div>
            <p className={styles.label}>5 stars</p>
            <RatingStars rating={5} reviewCount={1} />
          </div>
          <div>
            <p className={styles.label}>Fractional 2.3</p>
            <RatingStars rating={2.3} reviewCount={88} />
          </div>
        </div>
      </Section>

      {/* 3. PriceDisplay */}
      <Section title="3. PriceDisplay">
        <div className={styles.row}>
          <div>
            <p className={styles.label}>24% discount</p>
            <PriceDisplay salePrice={18900} originalPrice={25000} discountRate={24} />
          </div>
          <div>
            <p className={styles.label}>No discount</p>
            <PriceDisplay salePrice={28000} originalPrice={28000} discountRate={0} />
          </div>
          <div>
            <p className={styles.label}>70% off</p>
            <PriceDisplay salePrice={5900} originalPrice={19900} discountRate={70} />
          </div>
        </div>
      </Section>

      {/* 4. CouponChip */}
      <Section title="4. CouponChip">
        <div className={styles.row}>
          <CouponChip label="15% 쿠폰" state="available" />
          <CouponChip label="3,000원 쿠폰" state="downloaded" />
          <CouponChip label="5% 쿠폰" state="expired" />
        </div>
      </Section>

      {/* 5. ReviewBlock */}
      <Section title="5. ReviewBlock">
        <div className={styles.reviewWrap}>
          <ReviewBlock
            reviewId={1}
            authorMaskedId="cos***"
            rating={5}
            date="2025-11-15"
            body="정말 좋은 제품입니다! 달팽이 에센스인데 냄새도 전혀 없고 흡수도 잘 돼요. 피부가 촉촉해지고 탄력이 생긴 느낌이에요. 꾸준히 쓰면 더 좋아질 것 같아서 재구매 예정입니다. 추천합니다!"
            photoUrls={[]}
            helpfulCount={142}
          />
          <ReviewBlock
            reviewId={2}
            authorMaskedId="inn***"
            rating={4}
            date="2025-10-03"
            body="전반적으로 만족스럽습니다. 다만 용량 대비 가격이 살짝 아쉬운 편이에요. 그래도 효과는 확실하게 있어서 계속 쓸 것 같습니다."
            photoUrls={[]}
            helpfulCount={27}
          />
        </div>
      </Section>

      {/* 6. ImageCarousel */}
      <Section title="6. ImageCarousel">
        <div className={styles.carouselWrap}>
          <ImageCarousel images={SAMPLE_IMAGES} />
        </div>
        <div className={styles.carouselWrap} style={{ marginTop: 'var(--space-4)' }}>
          <p className={styles.label} style={{ marginBottom: 'var(--space-2)' }}>Single image (no nav)</p>
          <ImageCarousel images={[SAMPLE_IMAGES[0]]} />
        </div>
      </Section>

      {/* 7. FilterBar */}
      <Section title="7. FilterBar">
        <div className={styles.filterDemo}>
          <FilterBar
            sort={sort}
            onSortChange={setSort}
            appliedFilters={filters}
            onRemoveFilter={removeFilter}
            onFilterClick={() => alert('필터 시트 오픈')}
          />
          <p className={styles.label} style={{ padding: 'var(--space-4) var(--space-5)' }}>
            현재 정렬: {sort}
          </p>
        </div>
      </Section>

      {/* 8. QuantityOptionSelector */}
      <Section title="8. QuantityOptionSelector">
        <div className={styles.qtyWrap}>
          <QuantityOptionSelector options={SAMPLE_OPTIONS} unitPrice={20000} onChange={setSelectedOptions} />
          {selectedOptions.length > 0 && (
            <pre className={styles.jsonPreview}>
              {JSON.stringify(selectedOptions, null, 2)}
            </pre>
          )}
        </div>
      </Section>
    </div>
  )
}
