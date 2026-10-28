import { Link, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchCart, updateCartItemQty, removeCartItem } from '@/lib/cart'
import { useRequireAuth } from '@/lib/useRequireAuth'
import type { CartItem } from '@/lib/types'
import { IS_DEMO } from '@/lib/demo'
import { DEMO_PRODUCTS } from '@/demo/fixtures'
import ShoppingSteps from '@/components/ShoppingSteps/ShoppingSteps'
import styles from './Cart.module.css'

function formatKrw(n: number): string {
  return n.toLocaleString('ko-KR') + '원'
}

const SHIPPING_THRESHOLD = 30_000
const SHIPPING_FEE = 3_000

// Derives a consistent hue from a product name for the thumbnail background
function nameHue(name: string): number {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) & 0xffffffff
  }
  return Math.abs(hash) % 360
}

function ShippingFeeNote({ totalAmount }: { totalAmount: number }) {
  if (totalAmount >= SHIPPING_THRESHOLD) {
    return <span className={styles.shippingFree}>무료배송</span>
  }
  const remaining = SHIPPING_THRESHOLD - totalAmount
  return (
    <span className={styles.shippingNote}>
      {formatKrw(remaining)} 더 구매 시 무료배송
    </span>
  )
}

interface CartItemRowProps {
  item: CartItem
  onUpdateQty: (cartItemId: number, qty: number) => void
  onRemove: (cartItemId: number) => void
  isUpdating: boolean
}

function CartItemRow({ item, onUpdateQty, onRemove, isUpdating }: CartItemRowProps) {
  const maxQty = item.availableQuantity ?? 99
  const hue = nameHue(item.productName)
  const initial = item.productName.charAt(0)
  // Newer cart responses carry productId/thumbnailUrl; older demo state falls back to the seed.
  const demoProduct = IS_DEMO ? DEMO_PRODUCTS.find((product) => product.options.some((option) => option.id === item.productOptionId)) : null
  const productId = typeof item.productId === 'number' ? item.productId : demoProduct?.id
  const image = (typeof item.thumbnailUrl === 'string' && item.thumbnailUrl) || demoProduct?.images[0]

  return (
    <li className={styles.itemRow} aria-label={`${item.productName}${item.optionName ? ' ' + item.optionName : ''}`}>
      {/* Thumbnail: product image, or a colored initial avatar when the line has none */}
      <div
        className={styles.thumb}
        aria-hidden="true"
        style={{
          background: `hsl(${hue} 55% 90%)`,
          color: `hsl(${hue} 55% 35%)`,
        }}
      >
        {image ? <img src={image} alt="" /> : <span className={styles.thumbInitial}>{initial}</span>}
      </div>

      <div className={styles.itemInfo}>
        {productId !== undefined ? <Link to={`/products/${productId}`} className={styles.itemName}>{item.productName}</Link> : <p className={styles.itemName}>{item.productName}</p>}
        {item.optionName && (
          <p className={styles.itemOption}>{item.optionName}</p>
        )}
        <p className={styles.itemPrice}>{formatKrw(item.salePrice)}</p>
      </div>

      <div className={styles.itemControls}>
        {/* Qty stepper */}
        <div className={styles.stepper} role="group" aria-label={`${item.productName} 수량`}>
          <button
            type="button"
            className={styles.stepBtn}
            onClick={() => onUpdateQty(item.cartItemId, item.quantity - 1)}
            disabled={item.quantity <= 1 || isUpdating}
            aria-label={`${item.productName} 수량 줄이기`}
          >
            −
          </button>
          <span
            className={styles.stepCount}
            aria-live="polite"
            aria-label={`수량 ${item.quantity}`}
          >
            {item.quantity}
          </span>
          <button
            type="button"
            className={styles.stepBtn}
            onClick={() => onUpdateQty(item.cartItemId, item.quantity + 1)}
            disabled={item.quantity >= maxQty || isUpdating}
            aria-label={`${item.productName} 수량 늘리기`}
          >
            +
          </button>
        </div>

        <p className={styles.lineTotal}>{formatKrw(item.lineSubtotal)}</p>

        {/* Remove */}
        <button
          type="button"
          className={styles.removeBtn}
          onClick={() => onRemove(item.cartItemId)}
          aria-label={`${item.productName} 삭제`}
          disabled={isUpdating}
        >
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            aria-hidden="true"
          >
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>
    </li>
  )
}

export default function Cart() {
  useRequireAuth()

  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: cart, isLoading, isFetching, isError, error, refetch } = useQuery({
    queryKey: ['cart'],
    queryFn: ({ signal }) => fetchCart(signal),
  })

  const updateMutation = useMutation({
    mutationFn: ({ cartItemId, quantity }: { cartItemId: number; quantity: number }) =>
      updateCartItemQty(cartItemId, { quantity }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cart'] })
    },
  })

  const removeMutation = useMutation({
    mutationFn: (cartItemId: number) => removeCartItem(cartItemId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cart'] })
    },
  })

  const isMutating = updateMutation.isPending || removeMutation.isPending

  const handleUpdateQty = (cartItemId: number, quantity: number) => {
    if (quantity < 1) return
    updateMutation.mutate({ cartItemId, quantity })
  }

  const handleRemove = (cartItemId: number) => {
    removeMutation.mutate(cartItemId)
  }

  const shippingFee =
    cart && cart.totalAmount > 0 && cart.totalAmount < SHIPPING_THRESHOLD
      ? SHIPPING_FEE
      : 0

  const orderTotal = cart ? cart.totalAmount + shippingFee : 0

  if (isLoading) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className={styles.skeleton} aria-busy="true" aria-label="장바구니 로딩 중" />
        </div>
      </div>
    )
  }

  if (isError) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <p className={styles.errorMsg} role="alert">
            장바구니를 불러오지 못했습니다: {(error as Error).message}
          </p>
          <button type="button" className={styles.emptyLink} onClick={() => refetch()}>다시 시도</button>
        </div>
      </div>
    )
  }

  const isEmpty = !cart || cart.items.length === 0

  return (
    <div className={styles.page}>
      <div className="app-container">
        <div className={styles.pageHeader}>
          <ShoppingSteps current={1} />
          <h1 className={styles.heading}>장바구니</h1>
        </div>
        {(updateMutation.isError || removeMutation.isError) && (
          <p className={styles.errorMsg} role="alert">상품을 변경하지 못했습니다. 수량이나 삭제 버튼을 다시 눌러주세요.</p>
        )}

        {isEmpty ? (
          <div className={styles.empty}>
            <div className={styles.emptyIcon} aria-hidden="true">
              <svg
                width="56"
                height="56"
                viewBox="0 0 24 24"
                fill="none"
                stroke="var(--grey-300)"
                strokeWidth="1.5"
              >
                <circle cx="9" cy="21" r="1" />
                <circle cx="20" cy="21" r="1" />
                <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6" />
              </svg>
            </div>
            <p className={styles.emptyTitle}>장바구니가 비어있어요</p>
            <p className={styles.emptyText}>원하는 상품을 담아보세요.</p>
            <Link to="/" className={styles.emptyLink}>
              쇼핑 계속하기
            </Link>
          </div>
        ) : (
          <div className={styles.layout}>
            {/* Items */}
            <section className={styles.itemsSection} aria-label="장바구니 상품">
              <ul className={styles.itemList}>
                {cart.items.map((item: CartItem) => (
                  <CartItemRow
                    key={item.cartItemId}
                    item={item}
                    onUpdateQty={handleUpdateQty}
                    onRemove={handleRemove}
                    isUpdating={isMutating}
                  />
                ))}
              </ul>
            </section>

            {/* Summary */}
            <aside className={styles.summary} aria-label="주문 요약">
              <h2 className={styles.summaryTitle}>결제 예정 금액</h2>

              <dl className={styles.summaryList}>
                <div className={styles.summaryRow}>
                  <dt>상품 금액</dt>
                  <dd>{formatKrw(cart.totalAmount)}</dd>
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
              </dl>

              <div className={styles.shippingNote}>
                <ShippingFeeNote totalAmount={cart.totalAmount} />
              </div>

              <div className={styles.totalRow}>
                <span className={styles.totalLabel}>합계</span>
                <strong className={styles.totalAmount}>{formatKrw(orderTotal)}</strong>
              </div>

              <button
                type="button"
                className={styles.checkoutBtn}
                disabled={isMutating || isFetching}
                onClick={() => navigate('/checkout')}
              >
                주문하기 ({cart.totalItemCount}개)
              </button>
              <Link to="/search" className={styles.continueLink}>상품 더 담기</Link>
            </aside>
          </div>
        )}
      </div>
    </div>
  )
}
