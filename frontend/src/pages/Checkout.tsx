import { useState, useEffect, useRef } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchCart } from '@/lib/cart'
import { fetchAddresses, createOrder, confirmPayment, createAddress, insufficientPointsMessage } from '@/lib/orders'
import { getMySummary } from '@/lib/mypage'
import { apiGet, ApiError } from '@/lib/api'
import { useRequireAuth } from '@/lib/useRequireAuth'
import type { CreateAddressRequest } from '@/lib/types'
import styles from './Checkout.module.css'
import { IS_DEMO } from '@/lib/demo'
import { normalizeAddress, addressError } from '@/lib/address'
import ShoppingSteps from '@/components/ShoppingSteps/ShoppingSteps'

function formatKrw(n: number): string {
  return n.toLocaleString('ko-KR') + '원'
}

const SHIPPING_THRESHOLD = 30_000
const SHIPPING_FEE = 3_000

interface AddressFormData {
  recipientName: string
  phone: string
  zipcode: string
  addressMain: string
  addressDetail: string
  isDefault: boolean
}

const emptyForm: AddressFormData = {
  recipientName: '',
  phone: '',
  zipcode: '',
  addressMain: '',
  addressDetail: '',
  isDefault: false,
}

// Shape of GET /api/me/coupons response items (MemberCouponStatus: ISSUED, USED, EXPIRED, REVOKED)
interface MemberCouponItem {
  id: number
  couponId: number
  couponName: string
  discountType: 'FIXED_AMOUNT' | 'PERCENTAGE' | 'FREE_SHIPPING'
  discountValue: number
  minOrderAmount: number | null
  expiresAt: string | null
  status: 'ISSUED' | 'USED' | 'EXPIRED' | 'REVOKED'
  issuedAt: string | null
}

// Preview of the coupon discount. Mirrors CouponService.calculateDiscountAmount +
// OrderPricingCalculator (HALF_UP to the won) so the shown total matches what the
// server charges; the server remains the source of truth.
function calcCouponDiscount(coupon: MemberCouponItem, orderAmount: number): number {
  if (coupon.discountType === 'FIXED_AMOUNT') {
    return Math.min(coupon.discountValue, orderAmount)
  }
  if (coupon.discountType === 'PERCENTAGE') {
    return Math.round((orderAmount * coupon.discountValue) / 100)
  }
  return 0
}

export default function Checkout() {
  useRequireAuth()

  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // One Idempotency-Key per distinct order attempt: a retry after a network error
  // re-sends the same key (so the server returns the same order instead of creating
  // a duplicate); changing the cart, address, coupon, or points starts a new attempt.
  const attemptRef = useRef<{ signature: string; orderKey: string; paymentKey: string } | null>(null)

  const [selectedAddressId, setSelectedAddressId] = useState<number | null>(null)
  const [showAddressForm, setShowAddressForm] = useState(false)
  const [addressForm, setAddressForm] = useState<AddressFormData>(emptyForm)
  const [formError, setFormError] = useState<string | null>(null)
  const [invalidAddressField, setInvalidAddressField] = useState<string | null>(null)
  const [globalError, setGlobalError] = useState<string | null>(null)

  // Coupon + point state
  const [selectedCouponId, setSelectedCouponId] = useState<number | null>(null)
  const [showCouponPanel, setShowCouponPanel] = useState(false)
  const [pointInput, setPointInput] = useState('')

  // Queries
  const { data: cart, isLoading: cartLoading, isError: cartError, refetch: refetchCart } = useQuery({
    queryKey: ['cart'],
    queryFn: ({ signal }) => fetchCart(signal),
  })

  const { data: addresses, isLoading: addressesLoading, isError: addressesError, refetch: refetchAddresses } = useQuery({
    queryKey: ['addresses'],
    queryFn: ({ signal }) => fetchAddresses(signal),
  })

  const { data: mySummary, isLoading: summaryLoading, isError: summaryError, refetch: refetchSummary } = useQuery({
    queryKey: ['my-summary'],
    queryFn: ({ signal }) => getMySummary(signal),
  })

  const { data: coupons, isLoading: couponsLoading, isError: couponsError, refetch: refetchCoupons } = useQuery({
    queryKey: ['my-coupons'],
    // Fetch all coupons; filter ISSUED (usable) client-side
    queryFn: ({ signal }) => apiGet<MemberCouponItem[]>('/me/coupons', signal),
  })

  // Auto-select default address when addresses load
  useEffect(() => {
    if (addresses && addresses.length > 0 && selectedAddressId === null) {
      const defaultAddr = addresses.find((a) => a.isDefault) ?? addresses[0]
      setSelectedAddressId(defaultAddr.id)
    }
  }, [addresses, selectedAddressId])

  // Create address mutation
  const createAddressMutation = useMutation({
    mutationFn: (body: CreateAddressRequest) => createAddress(body),
    onSuccess: async (addr) => {
      setSelectedAddressId(addr.id)
      setShowAddressForm(false)
      setAddressForm(emptyForm)
      await refetchAddresses()
    },
    onError: (err: unknown) => {
      const msg = err instanceof ApiError ? err.message : '배송지 추가에 실패했습니다.'
      setFormError(msg)
    },
  })

  // Derived values
  const baseShippingFee =
    cart && cart.totalAmount > 0 && cart.totalAmount < SHIPPING_THRESHOLD
      ? SHIPPING_FEE
      : 0
  const productAmount = cart ? cart.totalAmount : 0

  const selectedCoupon = coupons?.find((c) => c.id === selectedCouponId && c.status === 'ISSUED' &&
    (c.minOrderAmount === null || productAmount >= c.minOrderAmount) &&
    (!c.expiresAt || new Date(c.expiresAt).getTime() > Date.now())) ?? null
  // A FREE_SHIPPING coupon waives the shipping fee (OrderPricing's freeShipping
  // flag; the demo mock does the same). The summary keeps the fee on its own row
  // and shows the waived amount as the coupon discount, like other coupons.
  const shippingFee = baseShippingFee
  const couponDiscount = selectedCoupon
    ? selectedCoupon.discountType === 'FREE_SHIPPING'
      ? baseShippingFee
      : calcCouponDiscount(selectedCoupon, productAmount)
    : 0

  const pointBalance = mySummary?.pointBalance ?? 0
  // The maximum points that can be applied is limited by both the balance
  // and the order subtotal (product + shipping) so points never exceed what's owed.
  const orderSubtotal = productAmount + shippingFee - couponDiscount
  const maxApplicablePoints = Math.min(pointBalance, Math.max(0, orderSubtotal))
  const usePointAmount = Math.max(0, Math.min(maxApplicablePoints, parseInt(pointInput || '0', 10) || 0))
  const requestedPoints = Number(pointInput || '0')
  const pointError = requestedPoints > pointBalance
    ? `보유 포인트(${formatKrw(pointBalance)})를 초과할 수 없습니다.`
    : requestedPoints > orderSubtotal ? '포인트는 주문금액을 초과할 수 없습니다.' : null

  const orderTotal = Math.max(
    0,
    orderSubtotal - usePointAmount,
  )

  const handlePointInputChange = (raw: string) => {
    // Allow only digits
    const digits = raw.replace(/[^0-9]/g, '')
    setPointInput(digits)
  }

  const handleUseAllPoints = () => {
    setPointInput(String(maxApplicablePoints))
  }

  // Place order mutation
  const placeMutation = useMutation({
    mutationFn: async () => {
      if (!cart || cart.items.length === 0) throw new Error('장바구니가 비어있습니다.')
      if (!selectedAddressId) throw new Error('배송지를 선택해주세요.')
      if (pointError) throw new Error(pointError)

      const body = {
        items: cart.items.map((item) => ({
          productOptionId: item.productOptionId,
          quantity: item.quantity,
        })),
        deliveryAddressId: selectedAddressId,
        couponId: selectedCoupon?.id ?? null,
        usePointAmount: usePointAmount > 0 ? usePointAmount : null,
      }
      const signature = JSON.stringify(body)
      if (attemptRef.current?.signature !== signature) {
        attemptRef.current = { signature, orderKey: crypto.randomUUID(), paymentKey: crypto.randomUUID() }
      }
      const { orderKey, paymentKey: paymentIdempotencyKey } = attemptRef.current

      const order = await createOrder(body, orderKey)

      const confirmed = await confirmPayment(
        {
          orderNo: order.orderNo,
          paymentKey: String(order.paymentKey),
          amount: order.amount,
        },
        paymentIdempotencyKey,
      )

      return { orderNo: confirmed.orderNo }
    },
    onSuccess: ({ orderNo }) => {
      attemptRef.current = null
      // The order consumed cart lines, points, and possibly a coupon.
      for (const key of ['cart', 'my-summary', 'mySummary', 'my-coupons', 'myOrders']) {
        void queryClient.invalidateQueries({ queryKey: [key] })
      }
      navigate(`/orders/${encodeURIComponent(orderNo)}`, { replace: true })
    },
    onError: (err: unknown) => {
      if (err instanceof ApiError && err.code === 'INSUFFICIENT_POINTS') {
        // The balance may have changed since the page loaded: reload it so the
        // inline check below the input reflects the server's current balance.
        void queryClient.invalidateQueries({ queryKey: ['my-summary'] })
        setGlobalError(insufficientPointsMessage(usePointAmount, orderSubtotal))
        return
      }
      const msg =
        err instanceof ApiError
          ? err.message
          : err instanceof Error
          ? err.message
          : '주문 처리 중 오류가 발생했습니다.'
      setGlobalError(msg)
    },
  })

  const handleAddressFormSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setFormError(null)
    setInvalidAddressField(null)
    const address = normalizeAddress(addressForm)
    const invalid = addressError(address)
    if (invalid) {
      setFormError(invalid.message)
      setInvalidAddressField(invalid.field)
      document.getElementById(invalid.field)?.focus()
      return
    }
    createAddressMutation.mutate(address)
  }

  const isLoading = cartLoading || addressesLoading || summaryLoading || couponsLoading

  if (isLoading) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className={styles.skeleton} aria-busy="true" aria-label="체크아웃 로딩 중" />
        </div>
      </div>
    )
  }

  if (cartError || addressesError || summaryError || couponsError) {
    return (
      <div className={styles.page}>
        <div className="app-container error-state" role="alert">
          <p>주문 정보를 불러오지 못했습니다. 다시 시도해주세요.</p>
          <button type="button" className={styles.saveAddressBtn} onClick={() => {
            void refetchCart(); void refetchAddresses(); void refetchSummary(); void refetchCoupons()
          }}>다시 시도</button>
          <Link to="/cart">장바구니로 돌아가기</Link>
        </div>
      </div>
    )
  }

  if (!cart || cart.items.length === 0) {
    return (
      <div className={styles.page}>
        <div className="app-container empty-state">
          <h1 className="empty-state__title">주문할 상품이 없습니다</h1>
          <p>먼저 원하는 상품을 장바구니에 담아주세요.</p>
          <Link to="/search" className={styles.recoveryLink}>상품 둘러보기</Link>
        </div>
      </div>
    )
  }

  // ISSUED = usable (not yet used, not expired/revoked)
  const availableCoupons = coupons?.filter((c) => c.status === 'ISSUED' &&
    (!c.expiresAt || new Date(c.expiresAt).getTime() > Date.now())) ?? []

  return (
    <div className={styles.page}>
      <div className="app-container">
        <div className={styles.pageHeader}>
          <ShoppingSteps current={2} />
          <h1 className={styles.heading}>주문 확인</h1>
          <Link to="/cart" className={styles.backLink}>장바구니 수정</Link>
        </div>

        <div className={styles.layout}>
          {/* Left column */}
          <div className={styles.leftCol}>
            {/* Address section */}
            <section className={styles.section} aria-label="배송지">
              <h2 className={styles.sectionTitle}>배송지</h2>

              {addresses && addresses.length > 0 ? (
                <ul className={styles.addressList}>
                  {addresses.map((addr) => (
                    <li key={addr.id}>
                      <label
                        className={`${styles.addressCard} ${selectedAddressId === addr.id ? styles.selected : ''}`}
                      >
                        <input
                          type="radio"
                          name="address"
                          className={styles.radioInput}
                          checked={selectedAddressId === addr.id}
                          onChange={() => setSelectedAddressId(addr.id)}
                          aria-label={`${addr.recipientName} 배송지 선택`}
                        />
                        <div className={styles.addressInfo}>
                          <p className={styles.addressName}>
                            {addr.recipientName}
                            {addr.isDefault && (
                              <span className={styles.defaultBadge}>기본</span>
                            )}
                          </p>
                          <p className={styles.addressText}>
                            ({addr.zipcode}) {addr.addressMain}
                            {addr.addressDetail ? ` ${addr.addressDetail}` : ''}
                          </p>
                          <p className={styles.addressPhone}>{addr.phone}</p>
                        </div>
                      </label>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className={styles.noAddress}>등록된 배송지가 없습니다.</p>
              )}

              <button
                type="button"
                className={styles.addAddressBtn}
                aria-expanded={showAddressForm}
                onClick={() => setShowAddressForm((v) => !v)}
              >
                {showAddressForm ? '닫기' : '+ 새 배송지 추가'}
              </button>

              {showAddressForm && (
                <form
                  className={styles.addressForm}
                  onSubmit={handleAddressFormSubmit}
                  noValidate
                >
                  {formError && (
                    <p id="address-error" className={styles.formError} role="alert">
                      {formError}
                    </p>
                  )}

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="recipientName">
                      수령인 *
                    </label>
                    <input
                      id="recipientName"
                      autoComplete="shipping name"
                      maxLength={100}
                      aria-invalid={invalidAddressField === 'recipientName'}
                      aria-describedby={invalidAddressField === 'recipientName' ? 'address-error' : undefined}
                      className={styles.formInput}
                      value={addressForm.recipientName}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, recipientName: e.target.value }))
                      }
                      placeholder="홍길동"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="phone">
                      연락처 *
                    </label>
                    <input
                      id="phone"
                      type="tel"
                      autoComplete="shipping tel"
                      maxLength={20}
                      aria-invalid={invalidAddressField === 'phone'}
                      aria-describedby={invalidAddressField === 'phone' ? 'address-error' : undefined}
                      className={styles.formInput}
                      value={addressForm.phone}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, phone: e.target.value }))
                      }
                      placeholder="010-0000-0000"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="zipcode">
                      우편번호 *
                    </label>
                    <input
                      id="zipcode"
                      autoComplete="shipping postal-code"
                      maxLength={10}
                      aria-invalid={invalidAddressField === 'zipcode'}
                      aria-describedby={invalidAddressField === 'zipcode' ? 'address-error' : undefined}
                      className={styles.formInput}
                      value={addressForm.zipcode}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, zipcode: e.target.value }))
                      }
                      placeholder="12345"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="addressMain">
                      기본 주소 *
                    </label>
                    <input
                      id="addressMain"
                      autoComplete="shipping address-line1"
                      maxLength={255}
                      aria-invalid={invalidAddressField === 'addressMain'}
                      aria-describedby={invalidAddressField === 'addressMain' ? 'address-error' : undefined}
                      className={styles.formInput}
                      value={addressForm.addressMain}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, addressMain: e.target.value }))
                      }
                      placeholder="서울시 강남구 테헤란로 123"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="addressDetail">
                      상세 주소
                    </label>
                    <input
                      id="addressDetail"
                      autoComplete="shipping address-line2"
                      maxLength={255}
                      className={styles.formInput}
                      value={addressForm.addressDetail}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, addressDetail: e.target.value }))
                      }
                      placeholder="101동 202호"
                    />
                  </div>

                  <label className={styles.checkboxRow}>
                    <input
                      type="checkbox"
                      checked={addressForm.isDefault}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, isDefault: e.target.checked }))
                      }
                    />
                    기본 배송지로 설정
                  </label>

                  <button
                    type="submit"
                    className={styles.saveAddressBtn}
                    disabled={createAddressMutation.isPending}
                    aria-busy={createAddressMutation.isPending}
                  >
                    {createAddressMutation.isPending ? '저장 중...' : '배송지 저장'}
                  </button>
                </form>
              )}
            </section>

            {/* Coupon section */}
            <section className={styles.section} aria-label="쿠폰">
              <h2 className={styles.sectionTitle}>쿠폰</h2>

              {availableCoupons.length === 0 ? (
                <p className={styles.noAddress}>사용 가능한 쿠폰이 없습니다.</p>
              ) : (
                <>
                  <button
                    type="button"
                    className={styles.couponToggleBtn}
                    onClick={() => setShowCouponPanel((v) => !v)}
                    aria-expanded={showCouponPanel}
                  >
                    {selectedCoupon
                      ? `선택됨: ${selectedCoupon.couponName}`
                      : `쿠폰 선택 (${availableCoupons.length}개 보유)`}
                    <svg
                      width="14"
                      height="14"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      aria-hidden="true"
                      className={showCouponPanel ? styles.chevronUp : ''}
                    >
                      <polyline points="6 9 12 15 18 9" />
                    </svg>
                  </button>

                  {showCouponPanel && (
                    <ul className={styles.couponList} aria-label="쿠폰 선택">
                      {/* Option: no coupon */}
                      <li>
                        <button
                          type="button"
                          className={`${styles.couponOption} ${selectedCouponId === null ? styles.couponOptionSelected : ''}`}
                          onClick={() => {
                            setSelectedCouponId(null)
                            setShowCouponPanel(false)
                          }}
                          aria-pressed={selectedCouponId === null}
                        >
                          쿠폰 사용 안 함
                        </button>
                      </li>

                      {availableCoupons.map((coupon) => {
                        const discount = calcCouponDiscount(coupon, productAmount)
                        const isSelected = selectedCouponId === coupon.id
                        const meetsMin =
                          coupon.minOrderAmount === null ||
                          productAmount >= coupon.minOrderAmount
                        return (
                          <li key={coupon.id}>
                            <button
                              type="button"
                              className={`${styles.couponOption} ${isSelected ? styles.couponOptionSelected : ''} ${!meetsMin ? styles.couponOptionDisabled : ''}`}
                              onClick={() => {
                                if (!meetsMin) return
                                setSelectedCouponId(coupon.id)
                                setShowCouponPanel(false)
                              }}
                              disabled={!meetsMin}
                              aria-pressed={isSelected}
                            >
                              <div className={styles.couponOptionRow}>
                                <span className={styles.couponName}>{coupon.couponName}</span>
                                <span className={styles.couponDiscount}>
                                  {coupon.discountType === 'FREE_SHIPPING' ? '배송비 무료' : `-${formatKrw(discount)}`}
                                </span>
                              </div>
                              {!meetsMin && coupon.minOrderAmount !== null && (
                                <p className={styles.couponMinNote}>
                                  {formatKrw(coupon.minOrderAmount)} 이상 주문 시 사용 가능
                                </p>
                              )}
                            </button>
                          </li>
                        )
                      })}
                    </ul>
                  )}
                </>
              )}
            </section>

            {/* Point section */}
            <section className={styles.section} aria-label="포인트">
              <h2 className={styles.sectionTitle}>포인트</h2>
              <p className={styles.pointBalance}>
                보유 포인트: <strong>{formatKrw(pointBalance)}</strong>
              </p>

              <div className={styles.pointInputRow}>
                <input
                  type="text"
                  inputMode="numeric"
                  className={`${styles.formInput} ${styles.pointInput} ${pointError ? styles.inputError : ''}`}
                  value={pointInput}
                  onChange={(e) => handlePointInputChange(e.target.value)}
                  placeholder="사용할 포인트"
                  aria-label="사용할 포인트 금액"
                  aria-describedby={pointError ? 'point-error' : undefined}
                  aria-invalid={Boolean(pointError)}
                />
                <button
                  type="button"
                  className={styles.pointAllBtn}
                  onClick={handleUseAllPoints}
                  disabled={maxApplicablePoints === 0}
                >
                  전액 사용
                </button>
              </div>

              {pointError && (
                <p id="point-error" className={styles.formError} role="alert">
                  {pointError}
                </p>
              )}

              {usePointAmount > 0 && !pointError && (
                <p className={styles.pointApplied}>
                  {formatKrw(usePointAmount)} 포인트 적용
                </p>
              )}
            </section>

            {/* Order items summary */}
            <section className={styles.section} aria-label="주문 상품">
              <h2 className={styles.sectionTitle}>주문 상품</h2>
              {cart && cart.items.length > 0 ? (
                <ul className={styles.orderItemList}>
                  {cart.items.map((item) => (
                    <li key={item.cartItemId} className={styles.orderItem}>
                      <span className={styles.orderItemName}>
                        {item.productName}
                        {item.optionName ? ` · ${item.optionName}` : ''}
                      </span>
                      <span className={styles.orderItemQty}>× {item.quantity}</span>
                      <span className={styles.orderItemPrice}>
                        {formatKrw(item.lineSubtotal)}
                      </span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className={styles.noAddress}>장바구니가 비어있습니다.</p>
              )}
            </section>
          </div>

          {/* Right: payment summary */}
          <aside className={styles.summary} aria-label="결제 정보">
            <h2 className={styles.summaryTitle}>결제 금액</h2>

            <dl className={styles.summaryList}>
              <div className={styles.summaryRow}>
                <dt>상품 금액</dt>
                <dd>{cart ? formatKrw(cart.totalAmount) : '-'}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>배송비</dt>
                <dd>
                  {shippingFee === 0 ? (
                    <span className={styles.freeLabel}>무료</span>
                  ) : (
                    formatKrw(shippingFee)
                  )}
                </dd>
              </div>
              {couponDiscount > 0 && (
                <div className={styles.summaryRow}>
                  <dt>쿠폰 할인</dt>
                  <dd className={styles.discountAmount}>-{formatKrw(couponDiscount)}</dd>
                </div>
              )}
              {usePointAmount > 0 && (
                <div className={styles.summaryRow}>
                  <dt>포인트 사용</dt>
                  <dd className={styles.discountAmount}>-{formatKrw(usePointAmount)}</dd>
                </div>
              )}
              {couponDiscount === 0 && usePointAmount === 0 && (
                <div className={styles.summaryRow}>
                  <dt>할인</dt>
                  <dd className={styles.noDiscount}>없음</dd>
                </div>
              )}
            </dl>

            <div className={styles.totalRow}>
              <span className={styles.totalLabel}>총 결제 금액</span>
              <strong className={styles.totalAmount}>{formatKrw(orderTotal)}</strong>
            </div>

            {globalError && (
              <p className={styles.globalError} role="alert">
                {globalError}
              </p>
            )}

            <button
              type="button"
              className={styles.placeOrderBtn}
              onClick={() => { setGlobalError(null); placeMutation.mutate() }}
              disabled={
                placeMutation.isPending ||
                !selectedAddressId ||
                !cart ||
                cart.items.length === 0 ||
                Boolean(pointError)
              }
              aria-busy={placeMutation.isPending}
            >
              {placeMutation.isPending ? '주문 처리 중...' : `${formatKrw(orderTotal)} ${IS_DEMO ? '데모 주문하기' : '결제하기'}`}
            </button>

            {!selectedAddressId && <p className={styles.disclaimer}>배송지를 추가하면 주문할 수 있습니다.</p>}
            <p className={styles.disclaimer}>{IS_DEMO ? '실제 결제와 배송 없이 주문 흐름만 체험합니다. 샘플 배송지를 입력해주세요.' : '주문 내용을 확인하였으며, 정보 제공 등에 동의합니다.'}</p>
          </aside>
        </div>
      </div>
    </div>
  )
}
