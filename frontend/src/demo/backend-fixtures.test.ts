import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { demoFetch, resetDemoState } from './mockApi'

interface Fixture {
  method: string
  path: string
  status: number
  request?: Record<string, unknown>
  body: Record<string, unknown>
  expectedHeaders?: Record<string, string>
}

const fixtures = Object.fromEntries(Object.entries(import.meta.glob<Fixture>('../../../contract/*.json', {
  eager: true, import: 'default',
})).map(([path, fixture]) => [path.split('/').pop()!.replace('.json', ''), fixture]))

// These boundaries belong to the server, and are never called by storefront pages.
const serverOnly = {
  'error-forbidden': 'Actuator authorization is a Spring Security boundary.',
  'error-webhook-signature': 'Payment-provider webhook authentication is server-only.',
  'webhook-ack': 'The payment provider, rather than the browser, sends webhooks.',
} as const

// Execute these requests too, but pin the deliberate demo behavior explicitly.
// They must not silently count as backend parity or fabricate customer reviews.
const demoDifferences: Record<string, { reference: string; reason: string; body?: Record<string, unknown> }> = {
  'error-validation': { reference: 'signup', reason: 'Demo signup does not run Bean Validation.' },
  'error-insufficient-stock': { reference: 'order-create', reason: 'Demo seed inventory is unlimited.' },
  'error-idempotency-conflict': { reference: 'order-create', reason: 'Demo replays an existing key without a payload fingerprint.' },
  'error-rate-limited': { reference: 'order-create', reason: 'Demo has no server-side rate limiter.' },
  'error-invalid-order-state': {
    reference: 'payment-confirm', reason: 'Demo has no pending-order expiry; the prepared normal order can still be paid.',
  },
  'product-reviews-example': { reference: 'product-reviews', reason: 'Demo intentionally exposes the empty seed review page.' },
  'error-page-garbage': {
    reference: 'products', reason: 'Demo coerces Infinity and JSON serializes its nonfinite page as null.',
    body: { success: true, data: [], meta: { page: null, size: 20, total: 13 } },
  },
  'error-size-fractional': {
    reference: 'products', reason: 'Demo accepts a fractional size and slices one product.',
    body: { ...fixtures.products.body, meta: { page: 0, size: 1.5, total: 13 } },
  },
}

type JsonObject = Record<string, any>
async function call(method: string, path: string, request?: unknown, headers: Record<string, string> = {}) {
  const pending = demoFetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', ...headers },
    body: request === undefined ? undefined : JSON.stringify(request),
  })
  await vi.runAllTimersAsync()
  const response = await pending
  return { response, body: await response.json() as JsonObject }
}

function fixture(name: string): Fixture {
  const found = fixtures[name]
  if (!found) throw new Error(`Missing shared fixture: ${name}`)
  return found
}

// Compare every required nested field and its type, including nullable inventory,
// timestamps and numeric payment keys. Generated IDs/tokens are not literal seed IDs.
function expectShape(actual: unknown, reference: unknown, path = 'body'): void {
  if (reference === null) {
    expect(actual, path).toBeNull()
  } else if (Array.isArray(reference)) {
    expect(Array.isArray(actual), path).toBe(true)
    const values = actual as unknown[]
    expect(values.length, path).toBe(reference.length)
    values.forEach((value, index) => expectShape(value, reference[index], `${path}[${index}]`))
  } else if (typeof reference === 'object') {
    expect(actual, path).toBeTypeOf('object')
    expect(actual, path).not.toBeNull()
    for (const [key, value] of Object.entries(reference as JsonObject)) {
      // Backend product lists add inStock; pages do not consume it and demo
      // inventory is unlimited. Keep every other product-list field required.
      if (key === 'inStock' && /^body\.data\[\d+\]$/.test(path) && 'productId' in (reference as JsonObject)) {
        expect(value, `${path}.${key}`).toBeTypeOf('boolean')
        continue
      }
      expect(actual, `${path}.${key}`).toHaveProperty(key)
      expectShape((actual as JsonObject)[key], value, `${path}.${key}`)
    }
  } else {
    expect(typeof actual, path).toBe(typeof reference)
    if (typeof reference === 'number') expect(Number.isFinite(actual), path).toBe(true)
  }
}

beforeEach(() => { resetDemoState(); vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers(); resetDemoState() })

describe('shared storefront fixture requests through the real demoFetch', () => {
  it('accounts explicitly for every server-only fixture', () => {
    for (const name of Object.keys(serverOnly)) expect(fixtures).toHaveProperty(name)
    for (const [name, contract] of Object.entries(fixtures)) {
      if (name in serverOnly) continue
      expect(contract.path, name).toMatch(/^\/api\//)
      expect(contract.path, name).not.toContain('/payments/webhook')
    }
  })

  it.each(Object.entries(fixtures).filter(([name]) => !(name in serverOnly)))(
    '%s executes the fixture method, request and path with generated identifiers', async (name, contract) => {
      let headers: Record<string, string> = {}
      let request = contract.request ? structuredClone(contract.request) : undefined
      let path = contract.path
      let login: JsonObject | undefined

      // The demo's documented new-email login creates this isolated member.
      // Signup stays unregistered; invalid credentials uses a real existing email.
      if (!['signup', 'login', 'error-validation', 'error-unauthorized', 'error-invalid-token'].includes(name)) {
        const auth = await call('POST', fixture('login').path, fixture('login').request)
        expect(auth.response.status).toBe(200)
        login = auth.body.data
        headers.Authorization = `Bearer ${login!.accessToken}`
      }
      if (name === 'error-invalid-credentials') {
        await call('POST', fixture('signup').path, {
          ...fixture('signup').request, email: request!.email, password: 'different-test-password',
        })
      }
      if (name === 'refresh') request!.refreshToken = login!.refreshToken

      if (['cart', 'cart-update', 'cart-delete'].includes(name)) {
        const added = await call('POST', fixture('cart-add').path, fixture('cart-add').request, headers)
        expect(added.response.status).toBe(201)
        path = path.replace('/10001', `/${added.body.data.cartItemId}`)
      }
      if (['wishlist', 'wishlist-delete'].includes(name)) {
        const added = await call('POST', fixture('wishlist-add').path, fixture('wishlist-add').request, headers)
        expect(added.response.status).toBe(200)
      }

      const paidSnapshot = ['orders', 'order-detail', 'my-summary', 'coupons'].includes(name)
      const orderRequest = contract.path === '/api/orders' && contract.method === 'POST'
      const paymentRequest = contract.path === '/api/payments/confirm'
      if (name === 'addresses' || paidSnapshot || orderRequest || paymentRequest) {
        const address = await call('POST', fixture('address-create').path, fixture('address-create').request, headers)
        expect(address.response.status).toBe(201)
        const coupons = await call('GET', fixture('coupons').path, undefined, headers)
        const coupon = coupons.body.data.find((value: JsonObject) => value.discountType === 'PERCENTAGE')
        expect(coupon).toBeDefined()
        if (request?.deliveryAddressId === 10001) request.deliveryAddressId = address.body.data.id
        if (request?.couponId === 10002) request.couponId = coupon.id

        headers['Idempotency-Key'] = 'shared-demo-contract-order'
        if (name === 'error-idempotency-required') delete headers['Idempotency-Key']
        if (paidSnapshot || paymentRequest || name === 'error-idempotency-conflict') {
          const expiryScenario = name === 'error-invalid-order-state'
          // Keep the fixture's 16000 payment amount: 20000 sunscreen, free
          // shipping and 4000 points. Only generated order/payment IDs change.
          const orderRequest = expiryScenario ? {
            items: [{ productOptionId: 1, quantity: 1 }],
            deliveryAddressId: address.body.data.id,
            couponId: coupons.body.data.find((value: JsonObject) => value.discountType === 'FREE_SHIPPING').id,
            usePointAmount: 4000,
          } : {
            ...fixture('order-create').request, deliveryAddressId: address.body.data.id, couponId: coupon.id,
          }
          const order = await call('POST', fixture('order-create').path, orderRequest, headers)
          expect(order.response.status).toBe(200)
          expect(order.body.data.amount).toBe(expiryScenario ? 16000 : 44360)
          path = path.replace('20261028-CONTRACT-00001', order.body.data.orderNo)
          if (paymentRequest) {
            request!.orderNo = order.body.data.orderNo
            request!.paymentKey = String(order.body.data.paymentKey)
          }
          if (paidSnapshot) {
            const paid = await call('POST', fixture('payment-confirm').path, {
              ...fixture('payment-confirm').request,
              orderNo: order.body.data.orderNo, paymentKey: String(order.body.data.paymentKey),
            }, headers)
            expect(paid.response.status).toBe(200)
          }
        }
      }

      const actual = await call(contract.method, path, request, headers)
      const difference = demoDifferences[name]
      const source = difference ? fixture(difference.reference) : contract
      const reference = difference?.body ? { ...source, body: difference.body } : source
      if (difference) expect(difference.reason.length).toBeGreaterThan(0)
      expect(actual.response.status).toBe(reference.status)
      expect(actual.body.success).toBe(reference.body.success)

      if (reference.body.success) {
        expectShape(actual.body, reference.body)
        // Spring NON_NULL omits void data; demo serializes null. Both are unused.
        if (!('data' in reference.body)) expect(actual.body.data).toBeNull()
        if (actual.body.meta) expect(actual.body.meta).toEqual(reference.body.meta)
        if (name === 'my-summary') {
          // The demo's new-email login names the member after the email prefix,
          // so only the name's type is shared; every total must match exactly.
          const { name: demoName, ...totals } = actual.body.data
          const { name: contractName, ...contractTotals } = reference.body.data as JsonObject
          expect(totals).toEqual(contractTotals)
          expect(demoName).toBeTypeOf(typeof contractName)
        }
        if (name === 'order-create') expect(actual.body.data.amount).toBe(44360)
        if (name === 'error-rate-limited') expect(actual.response.headers.get('Retry-After')).toBeNull()
        if (name === 'error-idempotency-conflict') expect(actual.body.data.amount).toBe(44360)
        if (name === 'error-invalid-order-state') {
          expect(actual.body.data.status).toBe('PAID')
          expect(actual.body.data.orderNo).toBe(request!.orderNo)
        }
      } else {
        const error = reference.body.error as JsonObject
        // traceId is server observability metadata, absent in the browser mock.
        const { traceId: _traceId, ...consumedError } = error
        expectShape(actual.body.error, consumedError, 'body.error')
        expect(actual.body.error.code).toBe(error.code)
        expect(actual.body.error.path).toBe(path.split('?')[0])
        expect(actual.body.error.message.length).toBeGreaterThan(0)
      }
    },
  )
})
