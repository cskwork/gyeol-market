import { Link, useLocation, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchOrderDetail } from '@/lib/orders'
import { useRequireAuth } from '@/lib/useRequireAuth'
import styles from './OrderComplete.module.css'
import { IS_DEMO } from '@/lib/demo'
import ShoppingSteps from '@/components/ShoppingSteps/ShoppingSteps'

function formatKrw(n: number): string {
  return n.toLocaleString('ko-KR') + '원'
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' })
}

export default function OrderComplete() {
  useRequireAuth()

  const location = useLocation()
  const params = useParams<{ orderNo?: string }>()

  // Accept orderNo from router state (from Checkout navigate) or from URL param
  const orderNo: string | undefined =
    (location.state as { orderNo?: string } | null)?.orderNo ?? params.orderNo

  const { data: order, isLoading, isError, error } = useQuery({
    queryKey: ['order', orderNo],
    queryFn: ({ signal }) => fetchOrderDetail(orderNo!, signal),
    enabled: Boolean(orderNo),
  })

  if (!orderNo) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className={styles.card}>
            <p className={styles.errorMsg}>주문 정보를 찾을 수 없습니다.</p>
            <Link to="/" className={styles.continueLink}>홈으로 이동</Link>
          </div>
        </div>
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className={styles.skeleton} aria-busy="true" aria-label="주문 정보 로딩 중" />
        </div>
      </div>
    )
  }

  if (isError || !order) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className={styles.card}>
            <p className={styles.errorMsg}>
              {isError ? (error as Error).message : '주문 정보를 불러올 수 없습니다.'}
            </p>
            <Link to="/" className={styles.continueLink}>홈으로 이동</Link>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className={styles.page}>
      <div className="app-container">
        <div className={styles.card}>
          <ShoppingSteps current={3} />
          {/* Success icon */}
          <div className={styles.iconWrap} aria-hidden="true">
            <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="24" fill="var(--action)" />
              <path
                d="M14 24.5L20.5 31L34 18"
                stroke="white"
                strokeWidth="3"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </div>

          <h1 className={styles.title}>{IS_DEMO ? '데모 주문이 완료되었습니다' : '주문이 완료되었습니다!'}</h1>
          {IS_DEMO && <p className={styles.subtitle}>실제 결제와 배송은 이루어지지 않습니다.</p>}
          <p className={styles.subtitle}>
            주문 번호: <strong className={styles.orderNo}>{order.orderNo}</strong>
          </p>
          <p className={styles.date}>{formatDate(order.createdAt)}</p>

          {/* Order items */}
          <section className={styles.section} aria-label="주문 상품">
            <h2 className={styles.sectionTitle}>주문 상품</h2>
            <ul className={styles.itemList}>
              {order.items.map((item) => (
                <li key={item.id} className={styles.item}>
                  <div className={styles.itemMeta}>
                    <span className={styles.itemName}>{item.productName}</span>
                    {item.optionName && (
                      <span className={styles.itemOption}>{item.optionName}</span>
                    )}
                  </div>
                  <span className={styles.itemQty}>× {item.quantity}</span>
                  <span className={styles.itemPrice}>{formatKrw(item.totalAmount)}</span>
                </li>
              ))}
            </ul>
          </section>

          {/* Delivery */}
          {order.delivery && (
            <section className={styles.section} aria-label="배송지">
              <h2 className={styles.sectionTitle}>배송지</h2>
              <p className={styles.deliveryName}>{order.delivery.recipientName}</p>
              <p className={styles.deliveryAddr}>
                ({order.delivery.zipcode}) {order.delivery.addressMain}
                {order.delivery.addressDetail ? ` ${order.delivery.addressDetail}` : ''}
              </p>
              <p className={styles.deliveryPhone}>{order.delivery.phone}</p>
            </section>
          )}

          {/* Price summary */}
          <section className={styles.section} aria-label="결제 금액">
            <h2 className={styles.sectionTitle}>결제 금액</h2>
            <dl className={styles.priceList}>
              <div className={styles.priceRow}>
                <dt>상품 금액</dt>
                <dd>{formatKrw(order.totalProductAmount)}</dd>
              </div>
              {order.discountAmount > 0 && (
                <div className={styles.priceRow}>
                  <dt>할인</dt>
                  <dd className={styles.discount}>-{formatKrw(order.discountAmount)}</dd>
                </div>
              )}
              {order.pointUsedAmount > 0 && (
                <div className={styles.priceRow}>
                  <dt>포인트 사용</dt>
                  <dd className={styles.discount}>-{formatKrw(order.pointUsedAmount)}</dd>
                </div>
              )}
              <div className={styles.priceRow}>
                <dt>배송비</dt>
                <dd>{order.deliveryFee > 0 ? formatKrw(order.deliveryFee) : <span className={styles.freeLabel}>무료</span>}</dd>
              </div>
              <div className={`${styles.priceRow} ${styles.totalPayment}`}>
                <dt>최종 결제</dt>
                <dd>{formatKrw(order.finalPaymentAmount)}</dd>
              </div>
            </dl>
          </section>

          {/* CTA */}
          <Link to="/" className={styles.continueLink}>
            쇼핑 계속하기
          </Link>
          <Link to="/orders" className={styles.historyLink}>주문 내역 보기</Link>
        </div>
      </div>
    </div>
  )
}
