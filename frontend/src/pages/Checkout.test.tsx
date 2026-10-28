import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Checkout from './Checkout'
import { setAccessToken } from '@/lib/api'
import { insufficientPointsMessage } from '@/lib/orders'
import { byText, changeValue, click, envelope, errorEnvelope, renderRoute, settle, type Rendered } from '@/testing/render'

// Live mode (IS_DEMO is false under Vitest): the page talks to a stubbed backend.
const CART = {
  items: [{
    cartItemId: 1, productOptionId: 7, optionName: '기본', productName: '티트리 카밍 트러블 세럼 30ml',
    salePrice: 16800, onSale: true, availableQuantity: null, quantity: 1, lineSubtotal: 16800, productStatus: 'ON_SALE',
  }],
  totalItemCount: 1,
  totalAmount: 16800,
}
const COUPONS = [
  { id: 11, couponId: 1, couponName: '신규 회원 무료배송', discountType: 'FREE_SHIPPING', discountValue: 0, minOrderAmount: null, expiresAt: null, status: 'ISSUED', issuedAt: null },
  { id: 12, couponId: 2, couponName: '신규 회원 3000원 쿠폰', discountType: 'FIXED_AMOUNT', discountValue: 3000, minOrderAmount: 10000, expiresAt: null, status: 'ISSUED', issuedAt: null },
]
const ADDRESS = { id: 5, recipientName: '홍길동', phone: '010-0000-0000', zipcode: '06236', addressMain: '서울특별시 강남구 테헤란로 1', addressDetail: null, isDefault: true }

let orderResponse: () => Response
let orderBodies: unknown[]

beforeEach(() => {
  setAccessToken('token')
  orderBodies = []
  orderResponse = () => errorEnvelope(500, 'INTERNAL', 'unexpected')
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url === '/api/cart') return envelope(CART)
    if (method === 'GET' && url === '/api/me/addresses') return envelope([ADDRESS])
    if (method === 'GET' && url === '/api/me/summary') return envelope({ pointBalance: 5000, usableCouponCount: 2, totalOrderCount: 0, gradeName: 'BRONZE' })
    if (method === 'GET' && url === '/api/me/coupons') return envelope(COUPONS)
    if (method === 'POST' && url === '/api/orders') {
      orderBodies.push(JSON.parse(String(init?.body)))
      return orderResponse()
    }
    return errorEnvelope(404, 'NOT_FOUND', url)
  }))
})

let view: Rendered | undefined
afterEach(() => {
  view?.unmount()
  vi.unstubAllGlobals()
  setAccessToken(null)
})

const summaryText = (v: Rendered) =>
  (v.container.querySelector('aside[aria-label="결제 정보"]')?.textContent ?? '').replace(/\s+/g, ' ')

async function openCheckout(): Promise<Rendered> {
  const v = renderRoute(<Checkout />, { path: '/checkout' })
  await settle()
  expect(v.container.querySelector('aside[aria-label="결제 정보"]')).not.toBeNull()
  return v
}

describe('Checkout coupons', () => {
  it('lets a FREE_SHIPPING coupon be used and shows the waived fee as a coupon discount', async () => {
    view = await openCheckout()
    expect(summaryText(view)).toContain('배송비3,000원')
    expect(summaryText(view)).toContain('총 결제 금액19,800원')

    click(byText(view.container, 'button', '쿠폰 선택'))
    const freeShipping = byText<HTMLButtonElement>(view.container, 'ul[aria-label="쿠폰 선택"] button', '신규 회원 무료배송')
    expect(freeShipping?.disabled).toBe(false)
    expect(freeShipping?.textContent).not.toContain('사용할 수 없습니다')
    click(freeShipping)

    const text = summaryText(view)
    expect(text).toContain('배송비3,000원')
    expect(text).toContain('쿠폰 할인-3,000원')
    expect(text).toContain('총 결제 금액16,800원')
    expect(text).not.toContain('할인없음')

    // The order request carries the coupon so the server waives shipping too.
    orderResponse = () => errorEnvelope(400, 'COUPON_INVALID', '사용할 수 없는 쿠폰입니다.')
    click(byText(view.container, 'aside button', '결제하기'))
    await settle()
    expect(orderBodies).toEqual([expect.objectContaining({ couponId: 11 })])
  })

  it('keeps amount coupons working as before', async () => {
    view = await openCheckout()
    click(byText(view.container, 'button', '쿠폰 선택'))
    click(byText(view.container, 'ul[aria-label="쿠폰 선택"] button', '신규 회원 3000원 쿠폰'))
    expect(summaryText(view)).toContain('쿠폰 할인-3,000원')
    expect(summaryText(view)).toContain('총 결제 금액16,800원')
  })
})

describe('Checkout points errors', () => {
  it('explains a server-side INSUFFICIENT_POINTS for an in-range integer as a balance shortfall', async () => {
    view = await openCheckout()
    changeValue(view.container.querySelector<HTMLInputElement>('input[aria-label="사용할 포인트 금액"]'), '5000')
    expect(view.container.querySelector('#point-error')).toBeNull()

    // Another tab spent the points meanwhile; the server rejects with its generic message.
    orderResponse = () => errorEnvelope(400, 'INSUFFICIENT_POINTS', '보유 포인트 범위 안에서 정수로 입력해주세요.')
    click(byText(view.container, 'aside button', '결제하기'))
    await settle()

    const alert = byText(view.container, '[role="alert"]', '포인트')
    expect(alert?.textContent).toBe('보유 포인트가 부족합니다. 보유 포인트를 확인한 뒤 다시 입력해주세요.')
    expect(view.container.textContent).not.toContain('정수로 입력')
  })

  it('shows the balance limit inline before ordering', async () => {
    view = await openCheckout()
    changeValue(view.container.querySelector<HTMLInputElement>('input[aria-label="사용할 포인트 금액"]'), '6000')
    expect(view.container.querySelector('#point-error')?.textContent).toBe('보유 포인트(5,000원)를 초과할 수 없습니다.')
  })
})

describe('insufficientPointsMessage', () => {
  it('names the actual cause', () => {
    expect(insufficientPointsMessage(5000, 19800)).toBe('보유 포인트가 부족합니다. 보유 포인트를 확인한 뒤 다시 입력해주세요.')
    expect(insufficientPointsMessage(20000, 19800)).toBe('포인트는 주문금액을 초과할 수 없습니다.')
    expect(insufficientPointsMessage(10.5, 19800)).toBe('포인트는 0 이상의 정수로 입력해주세요.')
    expect(insufficientPointsMessage(-1, 19800)).toBe('포인트는 0 이상의 정수로 입력해주세요.')
  })
})
