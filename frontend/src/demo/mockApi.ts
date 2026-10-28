// In-browser stand-in for the Spring Boot API, used only when the storefront is
// built with VITE_DEMO=1. It answers the same /api paths with the same
// ApiResponse envelope, status codes, and error codes as the backend, so the
// pages and lib/api.ts run unchanged. State (members, cart, orders, wishlist)
// lives in localStorage; nothing leaves the browser.

import {
  DEMO_ACCOUNT,
  DEMO_BRANDS,
  DEMO_CATEGORIES,
  DEMO_COUPONS,
  DEMO_POPULAR_KEYWORDS,
  DEMO_PRODUCTS,
  DEMO_START_POINTS,
  DEFAULT_SHIPPING_FEE,
  FREE_SHIPPING_THRESHOLD,
  type DemoProduct,
} from './fixtures'
import { normalizeAddress, addressError } from '../lib/address'

// ---- persisted state --------------------------------------------------------

interface DemoMember {
  id: number
  email: string
  password: string
  name: string
  points: number
}
interface DemoCartLine { cartItemId: number; optionId: number; quantity: number }
interface DemoAddress {
  id: number
  recipientName: string
  phone: string
  zipcode: string
  addressMain: string
  addressDetail: string | null
  isDefault: boolean
}
interface DemoMemberCoupon { id: number; couponId: number; status: 'ISSUED' | 'USED'; issuedAt: string; expiresAt: string }
interface DemoOrderItem { id: number; productName: string; optionName: string; unitPrice: number; quantity: number; totalAmount: number }
interface DemoOrder {
  id: number
  orderNo: string
  memberId: number
  status: 'PAYMENT_PENDING' | 'PAID'
  totalProductAmount: number
  discountAmount: number
  pointUsedAmount: number
  deliveryFee: number
  finalPaymentAmount: number
  items: DemoOrderItem[]
  delivery: Omit<DemoAddress, 'id' | 'isDefault'>
  createdAt: string
  paymentKey: number
  idempotencyKey: string
  memberCouponId: number | null
  cartItemIds: number[]
}
interface DemoState {
  seq: number
  members: DemoMember[]
  tokens: Record<string, number> // access/refresh token -> member id
  carts: Record<number, DemoCartLine[]>
  addresses: Record<number, DemoAddress[]>
  coupons: Record<number, DemoMemberCoupon[]>
  wishlist: Record<number, { wishlistId: number; productId: number }[]>
  orders: DemoOrder[]
}

const STORAGE_KEY = 'demo.state.v1'

function freshState(): DemoState {
  return {
    seq: 1000,
    members: [{ id: 1, email: DEMO_ACCOUNT.email, password: DEMO_ACCOUNT.password, name: DEMO_ACCOUNT.name, points: DEMO_START_POINTS }],
    tokens: {},
    carts: {},
    addresses: {},
    coupons: {},
    wishlist: {},
    orders: [],
  }
}

function load(): DemoState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw) as DemoState
  } catch {
    // corrupted state: start over
  }
  return freshState()
}

function save(s: DemoState): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(s))
}

const nextId = (s: DemoState) => ++s.seq

// ---- envelope helpers -------------------------------------------------------

class HttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message)
  }
}

interface Result { status: number; body: unknown }

const ok = (data: unknown, meta?: { page: number; size: number; total: number }): Result => ({
  status: 200,
  body: meta ? { success: true, data, meta } : { success: true, data },
})

function page<T>(items: T[], q: URLSearchParams, defaultSize = 20): Result {
  const p = Math.max(0, Number(q.get('page') ?? 0) || 0)
  let size = Number(q.get('size') ?? defaultSize) || defaultSize
  if (size < 1 || size > 100) size = defaultSize
  return ok(items.slice(p * size, p * size + size), { page: p, size, total: items.length })
}

// ---- catalog projections (same shapes as ProductDtos) -----------------------

function discountRate(p: DemoProduct): number {
  if (p.basePrice === 0) return 0
  return Math.round(((p.basePrice - p.salePrice) / p.basePrice) * 1000) / 10
}

function listItem(p: DemoProduct) {
  return {
    productId: p.id,
    brandName: DEMO_BRANDS[p.brandId]?.name ?? null,
    productName: p.name,
    salePrice: p.salePrice,
    originalPrice: p.basePrice,
    discountRate: discountRate(p),
    thumbnailUrl: p.images[0] ?? null,
    rating: 0,
    reviewCount: 0,
  }
}

function detail(p: DemoProduct) {
  return {
    ...listItem(p),
    brandLogoUrl: null,
    description: p.description,
    options: p.options.map((o) => ({
      optionId: o.id,
      optionName: o.name,
      optionPrice: o.price,
      status: 'ON_SALE',
      availableQuantity: null,
    })),
    images: p.images.map((url, i) => ({ imageId: p.id * 10 + i, url, sortOrder: i + 1, isThumbnail: i === 0 })),
    categories: DEMO_CATEGORIES.filter((c) => p.categoryIds.includes(c.id)).map((c) => ({
      categoryId: c.id,
      categoryName: c.name,
      categorySlug: c.slug,
    })),
  }
}

function sortProducts(list: DemoProduct[], sort: string | null): DemoProduct[] {
  const copy = [...list]
  switch (sort) {
    case 'PRICE_ASC':
      return copy.sort((a, b) => a.salePrice - b.salePrice)
    case 'PRICE_DESC':
      return copy.sort((a, b) => b.salePrice - a.salePrice)
    case 'POPULAR':
      // No sales in a fresh seed; deepest discount first reads as "popular".
      return copy.sort((a, b) => discountRate(b) - discountRate(a) || a.id - b.id)
    case 'LATEST':
    case null:
      return copy.sort((a, b) => b.createdOrder - a.createdOrder)
    default:
      return copy.sort((a, b) => a.id - b.id)
  }
}

function findOption(optionId: number) {
  for (const p of DEMO_PRODUCTS) {
    const o = p.options.find((x) => x.id === optionId)
    if (o) return { product: p, option: o }
  }
  return null
}

function productOrThrow(id: number): DemoProduct {
  const p = DEMO_PRODUCTS.find((x) => x.id === id)
  if (!p) throw new HttpError(404, 'PRODUCT_NOT_FOUND', '상품을 찾을 수 없습니다.')
  return p
}

// ---- auth -------------------------------------------------------------------

function issueTokens(s: DemoState, memberId: number) {
  const rand = () => Math.random().toString(36).slice(2)
  const accessToken = `demo-access.${memberId}.${rand()}`
  const refreshToken = `demo-refresh.${memberId}.${rand()}`
  s.tokens[accessToken] = memberId
  s.tokens[refreshToken] = memberId
  if (!s.coupons[memberId]) {
    const now = new Date()
    const expires = new Date(now.getTime() + 90 * 86_400_000).toISOString()
    s.coupons[memberId] = DEMO_COUPONS.map((c) => ({
      id: nextId(s),
      couponId: c.couponId,
      status: 'ISSUED' as const,
      issuedAt: now.toISOString(),
      expiresAt: expires,
    }))
  }
  return { accessToken, refreshToken, expiresInSec: 1800 }
}

function requireMember(s: DemoState, headers: Headers): DemoMember {
  const auth = headers.get('Authorization') ?? ''
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : ''
  const memberId = token.startsWith('demo-access.') ? s.tokens[token] : undefined
  const member = s.members.find((m) => m.id === memberId)
  if (!member) throw new HttpError(401, 'UNAUTHORIZED', '로그인이 필요합니다.')
  return member
}

// ---- cart -------------------------------------------------------------------

function cartView(s: DemoState, memberId: number) {
  const items = (s.carts[memberId] ?? []).flatMap((line) => {
    const found = findOption(line.optionId)
    if (!found) return []
    const unit = found.product.salePrice + found.option.price
    return [{
      cartItemId: line.cartItemId,
      productOptionId: line.optionId,
      optionName: found.option.name,
      productName: found.product.name,
      salePrice: unit,
      onSale: true,
      availableQuantity: null,
      quantity: line.quantity,
      lineSubtotal: unit * line.quantity,
      productStatus: 'ON_SALE',
      productId: found.product.id,
      thumbnailUrl: found.product.images[0] ?? null,
    }]
  })
  return {
    items,
    totalItemCount: items.reduce((n, i) => n + i.quantity, 0),
    totalAmount: items.reduce((n, i) => n + i.lineSubtotal, 0),
  }
}

// ---- orders -----------------------------------------------------------------

function couponDiscount(couponId: number, subtotal: number): number {
  const c = DEMO_COUPONS.find((x) => x.couponId === couponId)
  if (!c) return 0
  if (c.discountType === 'FIXED_AMOUNT') return c.discountValue
  if (c.discountType === 'PERCENTAGE') return (subtotal * c.discountValue) / 100
  return 0 // Shipping coupons waive the delivery fee below, rather than product price.
}

function orderDetailView(o: DemoOrder) {
  return {
    id: o.id,
    orderNo: o.orderNo,
    status: o.status,
    totalProductAmount: o.totalProductAmount,
    discountAmount: o.discountAmount,
    pointUsedAmount: o.pointUsedAmount,
    deliveryFee: o.deliveryFee,
    finalPaymentAmount: o.finalPaymentAmount,
    items: o.items,
    delivery: o.delivery,
    createdAt: o.createdAt,
  }
}

function createOrder(s: DemoState, m: DemoMember, body: Record<string, unknown>, idemKey: string | null): Result {
  if (!idemKey) throw new HttpError(400, 'IDEMPOTENCY_KEY_REQUIRED', 'Idempotency-Key 헤더가 필요합니다.')
  const replay = s.orders.find((o) => o.memberId === m.id && o.idempotencyKey === idemKey)
  if (replay) {
    return ok({ orderNo: replay.orderNo, paymentKey: replay.paymentKey, amount: replay.finalPaymentAmount, pgCheckoutPayload: null })
  }

  const reqItems = (body.items as { productOptionId: number; quantity: number }[] | undefined) ?? []
  if (reqItems.length === 0) throw new HttpError(400, 'VALIDATION_FAILED', '주문 상품이 없습니다.')
  const address = (s.addresses[m.id] ?? []).find((a) => a.id === body.deliveryAddressId)
  if (!address) throw new HttpError(404, 'ADDRESS_NOT_FOUND', '배송지를 찾을 수 없습니다.')

  const items: DemoOrderItem[] = reqItems.map((ri) => {
    if (!Number.isInteger(ri.quantity) || ri.quantity < 1 || ri.quantity > 99) {
      throw new HttpError(400, 'VALIDATION_FAILED', '수량은 1~99개까지 가능합니다.')
    }
    const found = findOption(ri.productOptionId)
    if (!found) throw new HttpError(404, 'PRODUCT_NOT_FOUND', '상품을 찾을 수 없습니다.')
    const unit = found.product.salePrice + found.option.price
    return { id: nextId(s), productName: found.product.name, optionName: found.option.name, unitPrice: unit, quantity: ri.quantity, totalAmount: unit * ri.quantity }
  })
  const subtotal = items.reduce((n, i) => n + i.totalAmount, 0)

  let discount = 0
  let memberCouponId: number | null = null
  let freeShipping = false
  if (body.couponId != null) {
    const mc = (s.coupons[m.id] ?? []).find((c) => c.id === body.couponId)
    if (!mc || mc.status !== 'ISSUED' || new Date(mc.expiresAt).getTime() <= Date.now()) throw new HttpError(400, 'COUPON_INVALID', '사용할 수 없는 쿠폰입니다.')
    const coupon = DEMO_COUPONS.find((c) => c.couponId === mc.couponId)!
    if (subtotal < coupon.minOrderAmount) throw new HttpError(400, 'COUPON_INVALID', '쿠폰 최소 주문 금액을 충족하지 않습니다.')
    discount = couponDiscount(coupon.couponId, subtotal)
    memberCouponId = mc.id
    freeShipping = coupon.discountType === 'FREE_SHIPPING'
  }

  const points = Number(body.usePointAmount ?? 0)
  if (!Number.isInteger(points) || points < 0 || points > m.points) throw new HttpError(400, 'INSUFFICIENT_POINTS', '보유 포인트 범위 안에서 정수로 입력해주세요.')

  // Same rule as OrderPricingCalculator: fee below threshold, total floored at 0, HALF_UP to won.
  const shipping = !freeShipping && subtotal < FREE_SHIPPING_THRESHOLD ? DEFAULT_SHIPPING_FEE : 0
  if (points > subtotal - discount + shipping) throw new HttpError(400, 'INSUFFICIENT_POINTS', '포인트는 주문금액을 초과할 수 없습니다.')
  const total = Math.round(Math.max(0, subtotal - discount - points + shipping))

  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}` // local date, like the UI
  const order: DemoOrder = {
    id: nextId(s),
    orderNo: `${stamp}-DEMO-${String(s.seq).padStart(5, '0')}`,
    memberId: m.id,
    status: 'PAYMENT_PENDING',
    totalProductAmount: subtotal,
    discountAmount: Math.round(discount),
    pointUsedAmount: points,
    deliveryFee: shipping,
    finalPaymentAmount: total,
    items,
    delivery: {
      recipientName: address.recipientName,
      phone: address.phone,
      zipcode: address.zipcode,
      addressMain: address.addressMain,
      addressDetail: address.addressDetail,
    },
    createdAt: now.toISOString(),
    paymentKey: nextId(s),
    idempotencyKey: idemKey,
    memberCouponId,
    cartItemIds: (s.carts[m.id] ?? [])
      .filter((l) => reqItems.some((ri) => ri.productOptionId === l.optionId))
      .map((l) => l.cartItemId),
  }
  s.orders.push(order)
  return ok({ orderNo: order.orderNo, paymentKey: order.paymentKey, amount: total, pgCheckoutPayload: null })
}

function confirmPayment(s: DemoState, m: DemoMember, body: Record<string, unknown>): Result {
  const o = s.orders.find((x) => x.memberId === m.id && x.orderNo === body.orderNo)
  if (!o) throw new HttpError(404, 'ORDER_NOT_FOUND', '주문을 찾을 수 없습니다.')
  if (String(o.paymentKey) !== String(body.paymentKey) || Number(body.amount) !== o.finalPaymentAmount) {
    throw new HttpError(422, 'PAYMENT_AMOUNT_MISMATCH', '결제 금액이 주문 금액과 일치하지 않습니다.')
  }
  if (o.status !== 'PAID') {
    o.status = 'PAID'
    m.points -= o.pointUsedAmount
    const mc = (s.coupons[m.id] ?? []).find((c) => c.id === o.memberCouponId)
    if (mc) mc.status = 'USED'
    s.carts[m.id] = (s.carts[m.id] ?? []).filter((l) => !o.cartItemIds.includes(l.cartItemId))
  }
  return ok({ orderId: o.id, orderNo: o.orderNo, status: o.status, paymentKey: String(o.paymentKey) })
}

// ---- router -----------------------------------------------------------------

function route(s: DemoState, method: string, path: string, q: URLSearchParams, body: Record<string, unknown>, headers: Headers): Result {
  let m: RegExpMatchArray | null
  const seg = path.split('/').filter(Boolean)

  // Catalog (public)
  if (method === 'GET' && path === '/categories') {
    return ok({ categories: DEMO_CATEGORIES.map((c) => ({ id: c.id, name: c.name, slug: c.slug, children: [] })) })
  }
  if (method === 'GET' && (m = path.match(/^\/categories\/([^/]+)\/products$/))) {
    const id = Number(m[1])
    if (!Number.isInteger(id)) throw new HttpError(400, 'INVALID_PARAMETER', '잘못된 카테고리입니다.')
    if (!DEMO_CATEGORIES.some((c) => c.id === id)) throw new HttpError(404, 'CATEGORY_NOT_FOUND', '카테고리를 찾을 수 없습니다.')
    const list = DEMO_PRODUCTS.filter((p) => p.categoryIds.includes(id))
    return page(sortProducts(list, q.get('sort') ?? 'LATEST').map(listItem), q)
  }
  if (method === 'GET' && (path === '/products' || path === '/products/rankings' || path === '/products/best-sellers')) {
    let list = DEMO_PRODUCTS
    const cat = q.get('categoryId')
    const brand = q.get('brandId')
    if (cat) list = list.filter((p) => p.categoryIds.includes(Number(cat)))
    if (brand) list = list.filter((p) => p.brandId === Number(brand))
    const sort = path === '/products' ? q.get('sort') ?? 'LATEST' : 'POPULAR'
    return page(sortProducts(list, sort).map(listItem), q)
  }
  if (method === 'GET' && (m = path.match(/^\/products\/(\d+)$/))) {
    return ok(detail(productOrThrow(Number(m[1]))))
  }
  if (method === 'GET' && (m = path.match(/^\/products\/(\d+)\/reviews$/))) {
    productOrThrow(Number(m[1]))
    return page([], q, 10) // the seed has no reviews; we do not invent any
  }

  // Search (public)
  if (method === 'GET' && path === '/search/products') {
    const kw = (q.get('keyword') ?? '').trim().toLowerCase()
    const cat = q.get('categoryId')
    let list = DEMO_PRODUCTS.filter((p) =>
      !kw || [p.name, p.description, DEMO_BRANDS[p.brandId]?.name ?? ''].some((t) => t.toLowerCase().includes(kw)),
    )
    if (cat) list = list.filter((p) => p.categoryIds.includes(Number(cat)))
    const sort = q.get('sort')
    return page(sortProducts(list, sort === 'RELEVANCE' ? 'ID' : sort).map(listItem), q)
  }
  if (method === 'GET' && path === '/search/autocomplete') {
    const prefix = (q.get('prefix') ?? '').trim()
    const size = Number(q.get('size') ?? 10) || 10
    const suggestions = prefix
      ? DEMO_PRODUCTS.map((p) => p.name).filter((n) => n.includes(prefix)).slice(0, size)
      : []
    return ok({ suggestions })
  }
  if (method === 'GET' && path === '/search/popular') {
    return ok({ keywords: DEMO_POPULAR_KEYWORDS.map((keyword, i) => ({ keyword, rank: i + 1 })) })
  }

  // Auth
  if (method === 'POST' && path === '/auth/login') {
    const email = String(body.email ?? '').trim().toLowerCase()
    const password = String(body.password ?? '')
    let member = s.members.find((x) => x.email === email)
    if (member && member.password !== password) {
      throw new HttpError(401, 'INVALID_CREDENTIALS', '이메일 또는 비밀번호가 올바르지 않습니다.')
    }
    if (!member) {
      // Demo convenience: any new email signs straight in as a fresh member.
      member = { id: nextId(s), email, password, name: email.split('@')[0], points: DEMO_START_POINTS }
      s.members.push(member)
    }
    return ok(issueTokens(s, member.id))
  }
  if (method === 'POST' && path === '/auth/signup') {
    const email = String(body.email ?? '').trim().toLowerCase()
    if (s.members.some((x) => x.email === email)) throw new HttpError(409, 'EMAIL_DUPLICATED', '이미 가입된 이메일입니다.')
    const member = { id: nextId(s), email, password: String(body.password ?? ''), name: String(body.name ?? ''), points: DEMO_START_POINTS }
    s.members.push(member)
    return { status: 201, body: { success: true, data: { memberId: member.id } } }
  }
  if (method === 'POST' && path === '/auth/refresh') {
    const token = String(body.refreshToken ?? '')
    const memberId = token.startsWith('demo-refresh.') ? s.tokens[token] : undefined
    if (!memberId) throw new HttpError(401, 'INVALID_TOKEN', '세션이 만료되었습니다.')
    delete s.tokens[token]
    return ok(issueTokens(s, memberId))
  }

  // Everything below requires a member session.
  const me = requireMember(s, headers)

  if (method === 'POST' && path === '/auth/logout') {
    for (const [token, id] of Object.entries(s.tokens)) if (id === me.id) delete s.tokens[token]
    return ok(null)
  }

  if (method === 'POST' && path === '/cart/merge') return ok({ mergedItemCount: 0 })
  if (method === 'GET' && path === '/cart') return ok(cartView(s, me.id))
  if (method === 'POST' && path === '/cart/items') {
    const optionId = Number(body.productOptionId)
    const qty = Number(body.quantity)
    if (!findOption(optionId)) throw new HttpError(404, 'PRODUCT_OPTION_NOT_FOUND', '상품 옵션을 찾을 수 없습니다.')
    if (!Number.isInteger(qty) || qty < 1 || qty > 99) throw new HttpError(400, 'VALIDATION_FAILED', '수량은 1~99개까지 담을 수 있습니다.')
    const cart = (s.carts[me.id] ??= [])
    const line = cart.find((l) => l.optionId === optionId)
    if (line) line.quantity = Math.min(99, line.quantity + qty)
    else cart.push({ cartItemId: nextId(s), optionId, quantity: qty })
    const saved = cart.find((l) => l.optionId === optionId)!
    return { status: 201, body: { success: true, data: { cartItemId: saved.cartItemId, quantity: saved.quantity } } }
  }
  if ((m = path.match(/^\/cart\/items\/(\d+)$/))) {
    const cart = s.carts[me.id] ?? []
    const line = cart.find((l) => l.cartItemId === Number(m![1]))
    if (!line) throw new HttpError(404, 'CART_ITEM_NOT_FOUND', '장바구니 상품을 찾을 수 없습니다.')
    if (method === 'PATCH') {
      const qty = Number(body.quantity)
      if (!Number.isInteger(qty) || qty < 1 || qty > 99) throw new HttpError(400, 'VALIDATION_FAILED', '수량은 1~99개까지 가능합니다.')
      line.quantity = qty
      return ok(null)
    }
    if (method === 'DELETE') {
      s.carts[me.id] = cart.filter((l) => l !== line)
      return ok(null)
    }
  }

  if (method === 'GET' && path === '/me/addresses') return ok(s.addresses[me.id] ?? [])
  if (method === 'POST' && path === '/me/addresses') {
    const normalized = normalizeAddress({
      recipientName: String(body.recipientName ?? ''), phone: String(body.phone ?? ''),
      zipcode: String(body.zipcode ?? ''), addressMain: String(body.addressMain ?? ''),
      addressDetail: String(body.addressDetail ?? ''), isDefault: Boolean(body.isDefault),
    })
    const invalid = addressError(normalized)
    if (invalid) throw new HttpError(400, 'VALIDATION_FAILED', invalid.message)
    const list = (s.addresses[me.id] ??= [])
    const isDefault = Boolean(body.isDefault) || list.length === 0
    if (isDefault) list.forEach((a) => (a.isDefault = false))
    const addr: DemoAddress = {
      id: nextId(s),
      recipientName: normalized.recipientName,
      phone: normalized.phone,
      zipcode: normalized.zipcode,
      addressMain: normalized.addressMain,
      addressDetail: normalized.addressDetail ?? null,
      isDefault,
    }
    list.push(addr)
    return { status: 201, body: { success: true, data: addr } }
  }
  if (method === 'GET' && path === '/me/coupons') {
    return ok((s.coupons[me.id] ?? []).map((mc) => {
      const c = DEMO_COUPONS.find((x) => x.couponId === mc.couponId)!
      return { ...mc, couponName: c.couponName, discountType: c.discountType, discountValue: c.discountValue, minOrderAmount: c.minOrderAmount }
    }))
  }
  if (method === 'GET' && path === '/me/summary') {
    return ok({
      pointBalance: me.points,
      usableCouponCount: (s.coupons[me.id] ?? []).filter((c) => c.status === 'ISSUED').length,
      totalOrderCount: s.orders.filter((o) => o.memberId === me.id && o.status === 'PAID').length,
      gradeName: 'BRONZE',
      name: me.name,
    })
  }
  if (path === '/me/wishlist') {
    const list = (s.wishlist[me.id] ??= [])
    if (method === 'GET') {
      return page(list.map((w) => {
        const p = productOrThrow(w.productId)
        const li = listItem(p)
        return { wishlistId: w.wishlistId, productId: p.id, productName: p.name, brandName: li.brandName, thumbnailUrl: li.thumbnailUrl, salePrice: li.salePrice, originalPrice: li.originalPrice, discountRate: li.discountRate }
      }), q)
    }
    if (method === 'POST') {
      const productId = Number(body.productId)
      productOrThrow(productId)
      if (!list.some((w) => w.productId === productId)) list.unshift({ wishlistId: nextId(s), productId })
      return ok(null)
    }
  }
  if (method === 'DELETE' && (m = path.match(/^\/me\/wishlist\/(\d+)$/))) {
    s.wishlist[me.id] = (s.wishlist[me.id] ?? []).filter((w) => w.productId !== Number(m![1]))
    return ok(null)
  }

  if (method === 'POST' && path === '/orders') return createOrder(s, me, body, headers.get('Idempotency-Key'))
  if (method === 'POST' && path === '/payments/confirm') return confirmPayment(s, me, body)
  if (method === 'GET' && path === '/orders') {
    const status = q.get('status')
    const list = s.orders
      .filter((o) => o.memberId === me.id && (!status || o.status === status))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map((o) => ({ id: o.id, orderNo: o.orderNo, status: o.status, totalProductAmount: o.totalProductAmount, finalPaymentAmount: o.finalPaymentAmount, createdAt: o.createdAt }))
    return page(list, q, 10)
  }
  if (method === 'GET' && seg[0] === 'orders' && seg.length === 2) {
    const o = s.orders.find((x) => x.memberId === me.id && x.orderNo === decodeURIComponent(seg[1]))
    if (!o) throw new HttpError(404, 'ORDER_NOT_FOUND', '주문을 찾을 수 없습니다.')
    return ok(orderDetailView(o))
  }

  throw new HttpError(404, 'NOT_FOUND', '데모 모드에서는 지원하지 않는 기능입니다.')
}

/**
 * fetch-compatible entry point. `url` is the full request path including the
 * `/api` prefix, e.g. `/api/products?page=0`.
 */
export async function demoFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const parsed = new URL(url, 'http://demo.local')
  const path = parsed.pathname.replace(/^\/api/, '')
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  let body: Record<string, unknown> = {}
  if (typeof init.body === 'string' && init.body.length > 0) body = JSON.parse(init.body) as Record<string, unknown>

  if (init.signal?.aborted) throw new DOMException('Aborted', 'AbortError')

  const s = load()
  let result: Result
  try {
    result = route(s, method, path, parsed.searchParams, body, headers)
    save(s)
  } catch (err) {
    const e = err instanceof HttpError ? err : new HttpError(500, 'INTERNAL_ERROR', '데모 처리 중 오류가 발생했습니다.')
    result = { status: e.status, body: { success: false, error: { code: e.code, message: e.message, path: `/api${path}` } } }
  }
  // Commit browser-local state before the response delay: navigation must not
  // discard a write already reflected by an optimistic control.
  await new Promise((r) => setTimeout(r, 120))
  if (init.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  return new Response(JSON.stringify(result.body), {
    status: result.status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** Clears all demo data (cart, orders, members) from this browser. */
export function resetDemoState(): void {
  localStorage.removeItem(STORAGE_KEY)
}
