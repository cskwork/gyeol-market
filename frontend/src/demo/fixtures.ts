// Demo-mode catalog fixtures. Derived 1:1 from the Flyway seed migrations so the
// static demo shows the same catalog as a fresh local backend:
//   V3__product.sql                    1 brand, 3 root categories, sunscreen + 2 options
//   V15__demo_catalog_seed.sql         6 brands, 12 products, one "기본" option each
//   V16__local_demo_product_images.sql same-origin product image paths
//   V20__fictional_demo_brands.sql     fictional brand/product names and image paths
//   V6__promotion.sql                  3 demo coupons
// IDs follow the serial order the migrations produce on an empty database.

export interface DemoCategory {
  id: number
  name: string
  slug: string
}

export interface DemoProduct {
  id: number
  brandId: number
  name: string
  description: string
  basePrice: number
  salePrice: number
  categoryIds: number[]
  images: string[] // first entry is the thumbnail
  options: { id: number; name: string; price: number }[]
  createdOrder: number // higher = newer (insertion order)
}

export const DEMO_BRANDS: Record<number, { name: string; slug: string }> = {
  1: { name: '새봄담', slug: 'saebomdam' },
  2: { name: '도담랩', slug: 'dodamlab' },
  3: { name: '더마하랑', slug: 'dermaharang' },
  4: { name: '벨로아', slug: 'veloa' },
  5: { name: '힐로담', slug: 'hilodam' },
  6: { name: '솔향공방', slug: 'solhyang' },
  7: { name: '그린마루', slug: 'greenmaru' },
}

export const DEMO_CATEGORIES: DemoCategory[] = [
  { id: 1, name: '스킨케어', slug: 'skincare' },
  { id: 2, name: '메이크업', slug: 'makeup' },
  { id: 3, name: '헤어/바디', slug: 'hair-body' },
]

const img = (file: string) => `/images/products/${file}`

type Row = [brandId: number, name: string, description: string, base: number, sale: number, categoryId: number, image: string]

// V15 VALUES order -> product ids 2..13
const V15_ROWS: Row[] = [
  [2, '새벽이슬 수분 토너 500ml', '건조한 피부에 산뜻하게 흡수되는 대용량 수분 토너입니다.', 28000, 22400, 1, 'dodamlab-dew-toner-500ml.png'],
  [3, '카밍 시카 진정 크림 70ml', '민감한 피부를 촉촉하게 진정시키는 데일리 수분 크림입니다.', 36000, 25200, 1, 'dermaharang-calming-cica-cream-70ml.png'],
  [7, '티트리 카밍 트러블 세럼 30ml', '티트리와 시카 성분으로 번들거림 없이 케어하는 집중 세럼입니다.', 24000, 16800, 1, 'greenmaru-teatree-calming-serum-30ml.png'],
  [1, '약산성 버블 클렌징 폼 150ml', '풍성한 버블로 피부 장벽 부담을 줄인 약산성 클렌징 폼입니다.', 15000, 9900, 1, 'saebomdam-low-ph-bubble-cleansing-foam-150ml.png'],
  [5, '콜라겐 탄력 시트 마스크팩 10매', '피부 탄력과 윤기를 위한 에센스 듬뿍 시트 마스크 세트입니다.', 22000, 14900, 1, 'hilodam-collagen-sheet-mask-10ea.png'],
  [4, '컬링 픽서 워터프루프 마스카라 블랙', '번짐 없이 또렷한 속눈썹을 연출하는 워터프루프 마스카라입니다.', 18000, 12600, 2, 'veloa-curl-fixer-waterproof-mascara-black.png'],
  [4, '소프트 매트 립 틴트 로즈', '가볍게 밀착되는 로즈 컬러의 소프트 블러 립 틴트입니다.', 16000, 11200, 2, 'veloa-soft-matte-lip-tint-rose.png'],
  [4, '데일리 밀착 쿠션 파운데이션 21N', '얇게 밀착되면서 자연스러운 커버를 돕는 데일리 쿠션입니다.', 32000, 25600, 2, 'veloa-daily-fit-cushion-foundation-21n.png'],
  [6, '로즈마리 두피 쿨링 샴푸 500ml', '두피를 산뜻하게 씻어내는 로즈마리 쿨링 샴푸입니다.', 21000, 16800, 3, 'solhyang-rosemary-scalp-cooling-shampoo-500ml.png'],
  [6, '퍼퓸 바디워시 피그 480ml', '은은한 무화과 향으로 샤워 후 잔향을 남기는 바디워시입니다.', 19000, 15200, 3, 'solhyang-perfume-bodywash-fig-480ml.png'],
  [1, '시어버터 핸드크림 3종 세트', '휴대하기 좋은 보습 핸드크림 3종 기획 세트입니다.', 12000, 8900, 3, 'saebomdam-sheabutter-handcream-3set.png'],
  [3, '남성 올인원 플루이드 150ml', '스킨과 로션 단계를 한 번에 끝내는 산뜻한 남성 올인원입니다.', 26000, 19500, 1, 'dermaharang-men-all-in-one-fluid-150ml.png'],
]

export const DEMO_PRODUCTS: DemoProduct[] = [
  {
    id: 1,
    brandId: 1,
    name: '키즈 매일 선크림 SPF50+ PA++++',
    description:
      '어린이 피부에 안전한 무기자차 선크림으로, 물놀이나 수영 후에도 씻어낼 필요가 없습니다.',
    basePrice: 25000,
    salePrice: 20000,
    // V3 maps the sunscreen to every root category (CROSS JOIN).
    categoryIds: [1, 2, 3],
    images: [
      img('saebomdam-kids-suncream-spf50-thumb.png'),
      img('saebomdam-kids-suncream-detail-1.png'),
      img('saebomdam-kids-suncream-detail-2.png'),
    ],
    options: [
      { id: 1, name: '50ml', price: 0 },
      { id: 2, name: '100ml', price: 5000 },
    ],
    createdOrder: 1,
  },
  ...V15_ROWS.map(([brandId, name, description, basePrice, salePrice, categoryId, image], i): DemoProduct => ({
    id: i + 2,
    brandId,
    name,
    description,
    basePrice,
    salePrice,
    categoryIds: [categoryId],
    images: [img(image)],
    options: [{ id: i + 3, name: '기본', price: 0 }],
    createdOrder: i + 2,
  })),
]

// V6 demo coupons, issued to the demo member on first login.
export const DEMO_COUPONS = [
  { couponId: 1, couponName: '신규 회원 3000원 쿠폰', discountType: 'FIXED_AMOUNT', discountValue: 3000, minOrderAmount: 10000 },
  { couponId: 2, couponName: '10% 할인 쿠폰', discountType: 'PERCENTAGE', discountValue: 10, minOrderAmount: 30000 },
  { couponId: 3, couponName: '무료 배송 쿠폰', discountType: 'FREE_SHIPPING', discountValue: 0, minOrderAmount: 15000 },
] as const

/** Demo sign-in shown on the login screen. Any valid email/password also works. */
export const DEMO_ACCOUNT = { email: 'demo@example.com', password: 'demo1234', name: '데모 회원' }

/** Starting point balance for the demo member (not seeded; demo-only value). */
export const DEMO_START_POINTS = 5000

export const DEMO_POPULAR_KEYWORDS = ['선크림', '토너', '마스크팩', '쿠션', '샴푸', '립 틴트']

// Mirrors DomainProperties defaults used by OrderPricingCalculator.
export const FREE_SHIPPING_THRESHOLD = 30000
export const DEFAULT_SHIPPING_FEE = 3000
