import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError, apiDelete, apiGet, apiGetPage, apiPatch, apiPost, apiPostWithHeaders, clearTokens,
  getAccessToken, getRefreshToken, setAccessToken, setRefreshToken,
} from './api'
import type { CategoryTree } from './categories'
import type {
  AddToCartResponse, AddressResponse, ApiResponse, AutocompleteResponse, CartMergeResponse,
  CartResponse, ConfirmPaymentResponse, CreateOrderResponse, LoginResponse, MyOrderListItem,
  MySummary, OrderDetail, PopularResponse, ProductDetail, ProductListItem, SignupResponse,
  WishlistItem,
} from './types'

vi.mock('./demo', () => ({ IS_DEMO: false }))

interface ContractFixture {
  method: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  path: string
  status: number
  request?: unknown
  body: ApiResponse<unknown>
  expectedHeaders?: Record<string, string>
}

// Backend integration tests consume these same files; the real api.ts handles
// envelopes, errors, and refresh while only the network transport is stubbed.
const fixtures = import.meta.glob<ContractFixture>('../../../contract/*.json', {
  eager: true, import: 'default',
})
function fixture(name: string): ContractFixture {
  const found = fixtures[`../../../contract/${name}.json`]
  if (!found) throw new Error(`Missing contract fixture: ${name}`)
  return found
}
function response(contract: ContractFixture): Response {
  return new Response(JSON.stringify(contract.body), {
    status: contract.status,
    headers: { 'Content-Type': 'application/json', ...contract.expectedHeaders },
  })
}
function serve(name: string): ContractFixture {
  const contract = fixture(name)
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response(contract))))
  return contract
}
const pathOf = (contract: ContractFixture) => contract.path.replace(/^\/api/, '')
function usesIdempotencyKey(contract: ContractFixture): boolean {
  return contract.method === 'POST'
    && ['/orders', '/payments/confirm'].includes(pathOf(contract))
    && contract.body.error?.code !== 'IDEMPOTENCY_KEY_REQUIRED'
}
function send(contract: ContractFixture): Promise<unknown> {
  const path = pathOf(contract)
  if (contract.method === 'GET') return apiGetPage<unknown>(path)
  if (contract.method === 'PATCH') return apiPatch<unknown>(path, contract.request)
  if (contract.method === 'DELETE') return apiDelete<unknown>(path)
  if (usesIdempotencyKey(contract)) {
    return apiPostWithHeaders<unknown>(path, contract.request, { 'Idempotency-Key': 'contract-attempt' })
  }
  return apiPost<unknown>(path, contract.request)
}

beforeEach(() => { localStorage.clear(); clearTokens() })
afterEach(() => vi.unstubAllGlobals())

describe('shared backend wire contracts through api.ts', () => {
  it('preserves an invalid order state as a terminal 409 without refreshing or retrying payment', async () => {
    const contract = serve('error-invalid-order-state')
    setAccessToken('contract-access-token')
    setRefreshToken('contract-refresh-token')
    await expect(send(contract)).rejects.toMatchObject({
      name: 'ApiError', status: 409, code: 'INVALID_ORDER_STATE',
      message: contract.body.error?.message,
    })
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBe('contract-access-token')
    expect(getRefreshToken()).toBe('contract-refresh-token')
  })

  it.each(Object.entries(fixtures))('%s', async (_name, contract) => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response(contract)))
    // A 401 is exposed after one refresh and retry; refresh itself skips retry.
    const retried = contract.status === 401 && !pathOf(contract).startsWith('/auth/refresh')
    if (retried) {
      setRefreshToken('contract-old-refresh-token')
      fetchMock.mockResolvedValueOnce(response(contract))
        .mockResolvedValueOnce(response(fixture('refresh')))
        .mockResolvedValueOnce(response(contract))
    }
    vi.stubGlobal('fetch', fetchMock)
    const pending = send(contract)
    if (contract.body.success) {
      await expect(pending).resolves.toEqual(contract.method === 'GET'
        ? { data: contract.body.data, meta: contract.body.meta } : contract.body.data)
    } else {
      await expect(pending).rejects.toMatchObject({
        name: 'ApiError', status: contract.status,
        message: contract.body.error?.message, code: contract.body.error?.code,
      })
    }
    const init = fetchMock.mock.calls[0][1] as RequestInit
    // The actuator security fixture lives outside /api; its envelope still
    // exercises the API client's error parser, which always adds its base.
    expect(fetchMock.mock.calls[0][0]).toBe(`/api${pathOf(contract)}`)
    expect(init.method).toBe(contract.method)
    expect(init.body).toBe(contract.request === undefined ? undefined : JSON.stringify(contract.request))
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe(usesIdempotencyKey(contract) ? 'contract-attempt' : null)
    expect(fetchMock).toHaveBeenCalledTimes(retried ? 3 : 1)
  })
})

describe('fields consumed by storefront pages', () => {
  it('exposes category IDs, slugs, names, and child arrays', async () => {
    const contract = serve('categories')
    const result = await apiGet<CategoryTree>(pathOf(contract))
    expect(result.categories.map(({ id, name, slug, children }) => ({ id, name, slug, children }))).toEqual([
      { id: 1, name: '스킨케어', slug: 'skincare', children: [] },
      { id: 2, name: '메이크업', slug: 'makeup', children: [] },
      { id: 3, name: '헤어/바디', slug: 'hair-body', children: [] },
    ])
  })
  it.each(['products', 'category-products', 'rankings', 'best-sellers', 'search-products'])(
    '%s supplies product card prices, images, rating, and pagination', async (name) => {
      const contract = serve(name)
      const result = await apiGetPage<ProductListItem[]>(pathOf(contract))
      const product = result.data[0]
      expect(product.productId).toBeGreaterThan(0)
      expect(product.productName.length).toBeGreaterThan(0)
      expect(product.brandName).toEqual(expect.any(String))
      expect(product.salePrice).toBeGreaterThan(0)
      expect(product.originalPrice).toBeGreaterThanOrEqual(product.salePrice)
      expect(product.discountRate).toBeGreaterThan(0)
      expect(product.thumbnailUrl).toMatch(/^\/images\/products\//)
      expect(product.rating).toBe(0)
      expect(product.reviewCount).toBe(0)
      expect(result.meta).toMatchObject({ page: 0, size: expect.any(Number), total: expect.any(Number) })
    },
  )
  it('supplies detail, carousel, and option selector fields', async () => {
    const contract = serve('product-detail')
    const product = await apiGet<ProductDetail>(pathOf(contract))
    expect(product.productId).toBe(1)
    expect(product.productName).toBe('키즈 매일 선크림 SPF50+ PA++++')
    expect(product.description).toEqual(expect.any(String))
    expect(product.brandLogoUrl).toBeNull()
    expect(product.options.map(({ optionId, optionName, optionPrice, status, availableQuantity }) =>
      ({ optionId, optionName, optionPrice, status, availableQuantity }))).toEqual([
      { optionId: 1, optionName: '50ml', optionPrice: 0, status: 'ON_SALE', availableQuantity: null },
      { optionId: 2, optionName: '100ml', optionPrice: 5000, status: 'ON_SALE', availableQuantity: null },
    ])
    expect(product.images[0]).toMatchObject({ imageId: expect.any(Number), sortOrder: 1, isThumbnail: true })
    expect(product.images.every((image) => image.url.startsWith('/images/products/'))).toBe(true)
    expect(product.categories[0]).toEqual({ categoryId: 1, categoryName: '스킨케어', categorySlug: 'skincare' })
  })
  it('pins nonempty review fields as well as the seed empty page', async () => {
    // Mirrors the page-local ProductReview in pages/ProductDetail.tsx.
    interface ProductReview {
      id: number; productId: number; rating: number; title: string | null
      body: string; imageUrls: string[] | null; createdAt: string
    }
    const contract = serve('product-reviews-example')
    const result = await apiGetPage<ProductReview[]>(pathOf(contract))
    const review = result.data[0]
    expect(review.id).toBe(10001)
    expect(review.productId).toBe(1)
    expect(review.rating).toBe(4)
    expect(review.title).toBe('계약 검증용 리뷰')
    expect(review.body).toBe('테스트에서 생성하는 합성 리뷰입니다.')
    expect(review.imageUrls?.[0]).toMatch(/^\/images\/products\//)
    expect(new Date(review.createdAt).toISOString()).toBe('2026-10-28T00:00:00.000Z')
    expect(result.meta).toEqual({ page: 0, size: 5, total: 1 })
  })
  it('supplies suggestions and ordered keyword labels', async () => {
    let contract = serve('autocomplete')
    const autocomplete = await apiGet<AutocompleteResponse>(pathOf(contract))
    expect(autocomplete.suggestions).toEqual(['키즈 매일 선크림 SPF50+ PA++++'])
    contract = serve('popular')
    const popular = await apiGet<PopularResponse>(pathOf(contract))
    expect(popular.keywords[0]).toEqual({ keyword: '선크림', rank: 1 })
    expect(popular.keywords.map((keyword) => keyword.rank)).toEqual([1, 2, 3, 4, 5, 6])
  })
  it('supplies signup ID and token pairs with numeric expiration', async () => {
    let contract = serve('signup')
    const member = await apiPost<SignupResponse>(pathOf(contract), contract.request)
    expect(member.memberId).toBeGreaterThan(0)
    for (const name of ['login', 'refresh']) {
      contract = serve(name)
      const login = await apiPost<LoginResponse>(pathOf(contract), contract.request)
      expect(login.accessToken.split('.')).toHaveLength(3)
      expect(login.refreshToken).toEqual(expect.any(String))
      expect(login.expiresInSec).toBe(1800)
    }
  })
  it('accepts the NON_NULL logout envelope without data', async () => {
    const contract = serve('logout')
    await expect(apiPost<void>(pathOf(contract))).resolves.toBeUndefined()
    expect(contract.body).not.toHaveProperty('data')
  })
  it('keeps sensitive validation rejection values redacted', async () => {
    const contract = serve('error-validation')
    expect(contract.body.error?.fieldErrors).toEqual([
      { field: 'password', message: expect.any(String), rejectedValue: null },
    ])
    await expect(apiPost(pathOf(contract), contract.request)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('backend authentication error handling', () => {
  it('retries once using the rotated access token', async () => {
    const contract = fixture('products')
    setAccessToken('old-access-token')
    setRefreshToken('old-refresh-token')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(fixture('error-unauthorized')))
      .mockResolvedValueOnce(response(fixture('refresh')))
      .mockResolvedValueOnce(response(contract))
    vi.stubGlobal('fetch', fetchMock)
    expect((await apiGetPage<ProductListItem[]>(pathOf(contract))).data[0].productId).toBe(12)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/refresh')
    expect(fetchMock.mock.calls[1][1].body).toBe(JSON.stringify({ refreshToken: 'old-refresh-token' }))
    const tokens = fixture('refresh').body.data as LoginResponse
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe(`Bearer ${tokens.accessToken}`)
    expect(getAccessToken()).toBe(tokens.accessToken)
    expect(getRefreshToken()).toBe(tokens.refreshToken)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
  it('clears both tokens when refresh is rejected', async () => {
    setAccessToken('old-access-token')
    setRefreshToken('old-refresh-token')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(fixture('error-unauthorized')))
      .mockResolvedValueOnce(response(fixture('error-invalid-token')))
    vi.stubGlobal('fetch', fetchMock)
    await expect(apiPost('/auth/logout')).rejects.toMatchObject({
      status: 401, message: '인증이 만료되었습니다. 다시 로그인해주세요.', code: undefined,
    })
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
  it('preserves the order idempotency key and body after a token refresh', async () => {
    const contract = fixture('order-create')
    setAccessToken('old-access-token')
    setRefreshToken('old-refresh-token')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(fixture('error-unauthorized')))
      .mockResolvedValueOnce(response(fixture('refresh')))
      .mockResolvedValueOnce(response(contract))
    vi.stubGlobal('fetch', fetchMock)
    const order = await apiPostWithHeaders<CreateOrderResponse>(pathOf(contract), contract.request, {
      'Idempotency-Key': 'contract-retried-order',
    })
    expect(order.amount).toBe(44360)
    for (const index of [0, 2]) {
      const init = fetchMock.mock.calls[index][1] as RequestInit
      expect(init.method).toBe('POST')
      expect(new Headers(init.headers).get('Idempotency-Key')).toBe('contract-retried-order')
      expect(init.body).toBe(JSON.stringify(contract.request))
    }
    const tokens = fixture('refresh').body.data as LoginResponse
    expect(new Headers(fetchMock.mock.calls[2][1].headers).get('Authorization')).toBe(`Bearer ${tokens.accessToken}`)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})

describe('fields consumed by cart, checkout, and account pages', () => {
  it('exposes option pricing, quantity, sale state, and nullable inventory', async () => {
    const contract = serve('cart')
    const cart = await apiGet<CartResponse>(pathOf(contract))
    expect(cart.items).toHaveLength(1)
    const item = cart.items[0]
    expect(item.cartItemId).toEqual(expect.any(Number))
    expect(item).toMatchObject({
      productOptionId: 4, optionName: '기본', productName: '카밍 시카 진정 크림 70ml',
      salePrice: 25200, quantity: 2, lineSubtotal: 50400,
      onSale: true, productStatus: 'ON_SALE', availableQuantity: null,
      productId: 3, thumbnailUrl: '/images/products/dermaharang-calming-cica-cream-70ml.png',
    })
    expect(item.lineSubtotal).toBe(item.salePrice * item.quantity)
    expect(cart.totalItemCount).toBe(2)
    expect(cart.totalAmount).toBe(50400)
  })
  it('parses cart mutations with their real methods and omitted void data', async () => {
    let contract = serve('cart-add')
    const added = await apiPost<AddToCartResponse>(pathOf(contract), contract.request)
    expect(added.cartItemId).toEqual(expect.any(Number))
    expect(added.quantity).toBe(2)
    contract = serve('cart-update')
    await expect(apiPatch<void>(pathOf(contract), contract.request)).resolves.toBeUndefined()
    expect(contract.body).toEqual({ success: true })
    contract = serve('cart-delete')
    await expect(apiDelete<void>(pathOf(contract))).resolves.toBeUndefined()
    expect(contract.body).toEqual({ success: true })
    contract = serve('cart-merge')
    const merged = await apiPost<CartMergeResponse>(pathOf(contract), contract.request)
    expect(merged.mergedItemCount).toBe(0)
  })
  it('preserves normalized delivery fields and a null address detail', async () => {
    let contract = serve('address-create')
    const created = await apiPost<AddressResponse>(pathOf(contract), contract.request)
    expect(created.id).toEqual(expect.any(Number))
    expect(created).toMatchObject({
      recipientName: '계약 테스트', phone: '010-0000-0000', zipcode: '04500',
      addressMain: '서울시 계약 테스트로 1', addressDetail: null, isDefault: true,
    })
    contract = serve('addresses')
    const addresses = await apiGet<AddressResponse[]>(pathOf(contract))
    expect(addresses).toEqual([created])
  })
  it('exposes coupon ownership, eligibility, discounts, and expiration dates', async () => {
    // Checkout.tsx keeps this DTO local; these are the fields used by its selector.
    interface MemberCouponItem {
      id: number; couponId: number; couponName: string
      discountType: 'FIXED_AMOUNT' | 'PERCENTAGE' | 'FREE_SHIPPING'
      discountValue: number; minOrderAmount: number | null
      status: 'ISSUED' | 'USED' | 'EXPIRED' | 'REVOKED'
      issuedAt: string | null; expiresAt: string | null
    }
    const contract = serve('coupons')
    const coupons = await apiGet<MemberCouponItem[]>(pathOf(contract))
    expect(coupons.filter((coupon) => coupon.status === 'ISSUED')).toHaveLength(2)
    const percentage = coupons.find((coupon) => coupon.discountType === 'PERCENTAGE')!
    expect(percentage).toMatchObject({
      id: expect.any(Number), couponId: 2, couponName: '10% 할인 쿠폰',
      discountValue: 10, minOrderAmount: 30000, status: 'USED',
    })
    expect(Math.round(50400 * percentage.discountValue / 100)).toBe(5040)
    for (const coupon of coupons) {
      expect(Number.isFinite(Date.parse(coupon.issuedAt!))).toBe(true)
      expect(Date.parse(coupon.expiresAt!)).toBeGreaterThan(Date.parse(coupon.issuedAt!))
    }
  })
  it('reports paid order, point, and usable coupon totals', async () => {
    const contract = serve('my-summary')
    const summary = await apiGet<MySummary>(pathOf(contract))
    expect(summary).toEqual({ pointBalance: 4000, usableCouponCount: 2, totalOrderCount: 1, gradeName: 'BRONZE', name: '계약 검증' })
  })
  it('provides paginated wishlist card fields and bodyless mutations', async () => {
    let contract = serve('wishlist')
    const wishlist = await apiGetPage<WishlistItem[]>(pathOf(contract))
    expect(wishlist.meta).toEqual({ page: 0, size: 20, total: 1 })
    expect(wishlist.data[0]).toEqual({
      wishlistId: expect.any(Number), productId: 3, productName: '카밍 시카 진정 크림 70ml',
      brandName: '더마하랑', thumbnailUrl: '/images/products/dermaharang-calming-cica-cream-70ml.png',
      salePrice: 25200, originalPrice: 36000, discountRate: 30,
    })
    contract = serve('wishlist-add')
    await expect(apiPost<void>(pathOf(contract), contract.request)).resolves.toBeUndefined()
    expect(contract.body).toEqual({ success: true })
    contract = serve('wishlist-delete')
    await expect(apiDelete<void>(pathOf(contract))).resolves.toBeUndefined()
    expect(contract.body).toEqual({ success: true })
  })
  it('converts the numeric order payment key to the confirmation string', async () => {
    let contract = serve('order-create')
    const order = await apiPostWithHeaders<CreateOrderResponse>(pathOf(contract), contract.request, {
      'Idempotency-Key': 'contract-order-attempt',
    })
    expect(order.orderNo).toEqual(expect.any(String))
    expect(order.paymentKey).toEqual(expect.any(Number))
    expect(order.amount).toBe(44360)
    expect(order.pgCheckoutPayload).toBeNull()
    contract = serve('payment-confirm')
    const confirmation = await apiPostWithHeaders<ConfirmPaymentResponse>(pathOf(contract), {
      orderNo: order.orderNo, paymentKey: String(order.paymentKey), amount: order.amount,
    }, { 'Idempotency-Key': 'contract-payment-attempt' })
    expect(confirmation.orderId).toEqual(expect.any(Number))
    expect(confirmation.orderNo).toBe(order.orderNo)
    expect(confirmation.status).toBe('PAID')
    expect(confirmation.paymentKey).toBe(String(order.paymentKey))
  })
  it('exposes order totals, item snapshots, delivery, and ISO dates', async () => {
    let contract = serve('orders')
    const page = await apiGetPage<MyOrderListItem[]>(pathOf(contract))
    expect(page.meta).toEqual({ page: 0, size: 10, total: 1 })
    const order = page.data[0]
    expect(order.id).toEqual(expect.any(Number))
    expect(order.orderNo).toEqual(expect.any(String))
    expect(order.status).toBe('PAID')
    expect(order.totalProductAmount).toBe(50400)
    expect(order.finalPaymentAmount).toBe(44360)
    expect(new Date(order.createdAt).toISOString()).toBe('2026-10-28T00:00:00.000Z')
    contract = serve('order-detail')
    const detail = await apiGet<OrderDetail>(pathOf(contract))
    expect(detail).toMatchObject(order)
    expect(detail.discountAmount).toBe(5040)
    expect(detail.pointUsedAmount).toBe(1000)
    expect(detail.deliveryFee).toBe(0)
    expect(detail.finalPaymentAmount).toBe(
      detail.totalProductAmount - detail.discountAmount - detail.pointUsedAmount + detail.deliveryFee,
    )
    expect(detail.items).toEqual([{
      id: expect.any(Number), productName: '카밍 시카 진정 크림 70ml', optionName: '기본',
      unitPrice: 25200, quantity: 2, totalAmount: 50400,
    }])
    expect(detail.delivery).toEqual({
      recipientName: '계약 테스트', phone: '010-0000-0000', zipcode: '04500',
      addressMain: '서울시 계약 테스트로 1', addressDetail: null,
    })
  })
  it('parses the webhook acknowledgment without exposing payment credentials', async () => {
    const contract = serve('webhook-ack')
    const result = await apiPost<{ eventId: string; processed: boolean }>(pathOf(contract), contract.request)
    expect(result).toEqual({ eventId: 'contract-payment-event-1', processed: true })
  })
  it('keeps the rate-limit retry hint in response headers', async () => {
    const contract = serve('error-rate-limited')
    const retryAfter = response(contract).headers.get('Retry-After')
    expect(retryAfter).toMatch(/^[1-9]\d*$/)
    await expect(send(contract)).rejects.toMatchObject({ status: 429, code: 'RATE_LIMITED' })
  })
})
