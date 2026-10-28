import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listMyOrders } from '@/lib/orders'
import { useRequireAuth } from '@/lib/useRequireAuth'
import type { MyOrderListItem } from '@/lib/types'
import styles from './OrderHistory.module.css'

function formatKrw(n: number): string {
  return n.toLocaleString('ko-KR') + '원'
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' })
}

// Order.OrderStatus on the backend, serialized by name.
type StatusKey =
  | 'CREATED'
  | 'PAYMENT_PENDING'
  | 'PAID'
  | 'PREPARING'
  | 'SHIPPING'
  | 'DELIVERED'
  | 'CANCELED'
  | 'REFUND_REQUESTED'
  | 'REFUNDED'
  | 'FAILED'

interface StatusConfig {
  label: string
  className: string
}

const STATUS_MAP: Partial<Record<StatusKey, StatusConfig>> = {
  CREATED: { label: '주문 접수', className: styles.statusPending },
  PAYMENT_PENDING: { label: '결제 대기', className: styles.statusPending },
  PAID: { label: '결제 완료', className: styles.statusPaid },
  PREPARING: { label: '상품 준비 중', className: styles.statusPreparing },
  SHIPPING: { label: '배송 중', className: styles.statusShipped },
  DELIVERED: { label: '배송 완료', className: styles.statusDelivered },
  CANCELED: { label: '주문 취소', className: styles.statusCancelled },
  REFUND_REQUESTED: { label: '환불 신청', className: styles.statusRefund },
  REFUNDED: { label: '환불 완료', className: styles.statusRefund },
  FAILED: { label: '주문 실패', className: styles.statusCancelled },
}

function getStatusConfig(status: string): StatusConfig {
  const key = status as StatusKey
  return STATUS_MAP[key] ?? { label: status, className: styles.statusDefault }
}

const PAGE_SIZE = 10

const STATUS_FILTERS: Array<{ value: string; label: string }> = [
  { value: '', label: '전체' },
  { value: 'PAID', label: '결제 완료' },
  { value: 'PREPARING', label: '준비 중' },
  { value: 'SHIPPING', label: '배송 중' },
  { value: 'DELIVERED', label: '배송 완료' },
  { value: 'CANCELED', label: '주문 취소' },
]

function OrderRowSkeleton() {
  return (
    <li className={styles.orderRow} aria-hidden="true">
      <div className={styles.orderRowTop}>
        <div className={styles.skeletonInline} style={{ width: 120, height: 14 }} />
        <div className={styles.skeletonInline} style={{ width: 60, height: 22, borderRadius: 999 }} />
      </div>
      <div className={styles.skeletonInline} style={{ width: 180, height: 16, marginTop: 8 }} />
      <div className={styles.orderRowBottom}>
        <div className={styles.skeletonInline} style={{ width: 100, height: 18 }} />
        <div className={styles.skeletonInline} style={{ width: 80, height: 36, borderRadius: 8 }} />
      </div>
    </li>
  )
}

interface OrderRowProps {
  order: MyOrderListItem
}

function OrderRow({ order }: OrderRowProps) {
  const statusCfg = getStatusConfig(order.status)
  return (
    <li className={styles.orderRow}>
      <div className={styles.orderRowTop}>
        <time className={styles.orderDate} dateTime={order.createdAt}>
          {formatDate(order.createdAt)}
        </time>
        <span className={`${styles.statusBadge} ${statusCfg.className}`}>
          {statusCfg.label}
        </span>
      </div>

      <p className={styles.orderNo} aria-label={`주문 번호 ${order.orderNo}`}>
        주문번호 {order.orderNo}
      </p>

      <div className={styles.orderRowBottom}>
        <span className={styles.orderAmount}>{formatKrw(order.finalPaymentAmount)}</span>
        <Link
          to={`/orders/${order.orderNo}`}
          className={styles.detailLink}
          aria-label={`주문 ${order.orderNo} 상세 보기`}
        >
          상세 보기
        </Link>
      </div>
    </li>
  )
}

export default function OrderHistory() {
  useRequireAuth()

  const [statusFilter, setStatusFilter] = useState('')
  const [page, setPage] = useState(0)

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['myOrders', statusFilter, page],
    queryFn: ({ signal }) =>
      listMyOrders({ status: statusFilter || undefined, page, size: PAGE_SIZE }, signal),
  })

  const orders = data?.data ?? []
  const meta = data?.meta
  const hasMore = meta ? (page + 1) * PAGE_SIZE < meta.total : false

  const handleFilterChange = (value: string) => {
    setStatusFilter(value)
    setPage(0)
  }

  const handleLoadMore = () => {
    setPage((prev) => prev + 1)
  }

  return (
    <div className={styles.page}>
      <div className="app-container">
        <header className={styles.pageHeader}>
          <h1 className={styles.heading}>주문 내역</h1>
          {meta && (
            <span className={styles.totalCount} aria-live="polite">
              총 <strong>{meta.total}</strong>건
            </span>
          )}
        </header>

        {/* Status filter tabs */}
        <div className={styles.filterWrap} role="tablist" aria-label="주문 상태 필터">
          {STATUS_FILTERS.map((f) => (
            <button
              key={f.value}
              type="button"
              role="tab"
              aria-selected={statusFilter === f.value}
              className={`${styles.filterTab} ${statusFilter === f.value ? styles.filterTabActive : ''}`}
              onClick={() => handleFilterChange(f.value)}
            >
              {f.label}
            </button>
          ))}
        </div>

        {/* Loading skeleton */}
        {isLoading && (
          <ul className={styles.orderList} aria-label="주문 목록 로딩 중" aria-busy="true">
            {Array.from({ length: 3 }).map((_, i) => (
              <OrderRowSkeleton key={i} />
            ))}
          </ul>
        )}

        {/* Error state */}
        {isError && !isLoading && (
          <div className="error-state" role="alert">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <p>주문 내역을 불러오지 못했습니다.</p>
            {error instanceof Error && (
              <p style={{ fontSize: 12, color: 'var(--grey-500)' }}>{error.message}</p>
            )}
            <button
              type="button"
              className={styles.retryBtn}
              onClick={() => void refetch()}
            >
              다시 시도
            </button>
          </div>
        )}

        {/* Empty state */}
        {!isLoading && !isError && orders.length === 0 && (
          <div className="empty-state" role="status">
            <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="var(--grey-300)" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2" />
              <rect x="9" y="3" width="6" height="4" rx="1" />
            </svg>
            <p className="empty-state__title">주문 내역이 없습니다</p>
            <p>
              {statusFilter
                ? '해당 상태의 주문이 없습니다.'
                : '아직 주문하신 상품이 없어요.'}
            </p>
            <Link to="/" className={styles.shopLink}>
              쇼핑하러 가기
            </Link>
          </div>
        )}

        {/* Order list */}
        {!isLoading && !isError && orders.length > 0 && (
          <>
            <ul className={styles.orderList} aria-label="주문 목록">
              {orders.map((order) => (
                <OrderRow key={order.id} order={order} />
              ))}
            </ul>

            {hasMore && (
              <div className={styles.loadMoreWrap}>
                <button
                  type="button"
                  className={styles.loadMoreBtn}
                  onClick={handleLoadMore}
                  aria-label="주문 내역 더 보기"
                >
                  더 보기
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
