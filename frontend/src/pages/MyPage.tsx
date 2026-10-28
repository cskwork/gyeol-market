import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getMySummary } from '@/lib/mypage'
import { useRequireAuth } from '@/lib/useRequireAuth'
import styles from './MyPage.module.css'

function formatKrw(n: number): string {
  return n.toLocaleString('ko-KR')
}

interface StatCardProps {
  label: string
  value: string | number
  unit?: string
  accentColor?: string
}

function StatCard({ label, value, unit, accentColor }: StatCardProps) {
  return (
    <div className={styles.statCard}>
      <span className={styles.statValue} style={accentColor ? { color: accentColor } : undefined}>
        {value}
        {unit && <span className={styles.statUnit}>{unit}</span>}
      </span>
      <span className={styles.statLabel}>{label}</span>
    </div>
  )
}

interface NavCardProps {
  to: string
  icon: ReactNode
  title: string
  desc: string
}

function NavCard({ to, icon, title, desc }: NavCardProps) {
  return (
    <Link to={to} className={styles.navCard} aria-label={`${title} 페이지로 이동`}>
      <span className={styles.navIcon} aria-hidden="true">{icon}</span>
      <span className={styles.navText}>
        <span className={styles.navTitle}>{title}</span>
        <span className={styles.navDesc}>{desc}</span>
      </span>
      <svg
        className={styles.navArrow}
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <polyline points="9 18 15 12 9 6" />
      </svg>
    </Link>
  )
}

function GradeBadge({ grade }: { grade: string }) {
  return (
    <span className={styles.gradeBadge} aria-label={`회원 등급: ${grade}`}>
      {grade}
    </span>
  )
}

function SummarySkeleton() {
  return (
    <div className={styles.skeletonWrap} aria-busy="true" aria-label="마이페이지 로딩 중">
      <div className={`${styles.skeletonBlock} skeleton-shimmer`} style={{ height: 120, borderRadius: 12 }} />
      <div className={styles.skeletonStatRow}>
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className={`${styles.skeletonStat} skeleton-shimmer`} />
        ))}
      </div>
      <div className={`${styles.skeletonBlock} skeleton-shimmer`} style={{ height: 56, borderRadius: 12, marginTop: 8 }} />
      <div className={`${styles.skeletonBlock} skeleton-shimmer`} style={{ height: 56, borderRadius: 12 }} />
      <div className={`${styles.skeletonBlock} skeleton-shimmer`} style={{ height: 56, borderRadius: 12 }} />
    </div>
  )
}

export default function MyPage() {
  useRequireAuth()

  const { data: summary, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['mySummary'],
    queryFn: ({ signal }) => getMySummary(signal),
  })

  if (isLoading) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <SummarySkeleton />
        </div>
      </div>
    )
  }

  if (isError || !summary) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className="error-state" role="alert">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <p>마이페이지를 불러오지 못했습니다.</p>
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
        </div>
      </div>
    )
  }

  return (
    <div className={styles.page}>
      <div className="app-container">
        {/* Profile header */}
        <header className={styles.profileHeader}>
          <div className={styles.avatarWrap} aria-hidden="true">
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
              <circle cx="12" cy="8" r="4" />
              <path d="M4 20c0-4 3.6-7 8-7s8 3 8 7" />
            </svg>
          </div>
          <div className={styles.profileInfo}>
            <div className={styles.gradeRow}>
              <GradeBadge grade={summary.gradeName} />
            </div>
            <p className={styles.welcomeText}>안녕하세요, 회원님!</p>
          </div>
        </header>

        {/* Stats row */}
        <section className={styles.statsSection} aria-label="회원 혜택 현황">
          <StatCard
            label="포인트"
            value={formatKrw(summary.pointBalance)}
            unit="P"
            accentColor="var(--action)"
          />
          <div className={styles.statDivider} aria-hidden="true" />
          <StatCard
            label="쿠폰"
            value={summary.usableCouponCount}
            unit="장"
            accentColor="var(--coupon-blue)"
          />
          <div className={styles.statDivider} aria-hidden="true" />
          <StatCard
            label="주문"
            value={summary.totalOrderCount}
            unit="건"
          />
        </section>

        {/* Navigation cards */}
        <nav className={styles.navSection} aria-label="마이페이지 메뉴">
          <NavCard
            to="/orders"
            icon={
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2" />
                <rect x="9" y="3" width="6" height="4" rx="1" />
                <line x1="9" y1="12" x2="15" y2="12" />
                <line x1="9" y1="16" x2="13" y2="16" />
              </svg>
            }
            title="주문 내역"
            desc={`총 ${summary.totalOrderCount}건의 주문`}
          />
          <NavCard
            to="/wishlist"
            icon={
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
              </svg>
            }
            title="찜 목록"
            desc="관심 상품을 모아보세요"
          />
          <NavCard
            to="/cart"
            icon={
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="9" cy="21" r="1" />
                <circle cx="20" cy="21" r="1" />
                <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6" />
              </svg>
            }
            title="장바구니"
            desc="담아둔 상품을 확인하세요"
          />
        </nav>
      </div>
    </div>
  )
}
