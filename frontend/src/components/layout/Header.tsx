import { useState, useEffect, useRef, useCallback } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getAccessToken, clearTokens, apiPost } from '@/lib/api'
import { useCategoryTree, pouchStyle, type CategoryNode } from '@/lib/categories'
import { fetchCart } from '@/lib/cart'
import styles from './Header.module.css'

interface NavCategory {
  id: string
  label: string
  slug: string
  subs: { label: string; href: string }[]
}

function toNavCategories(nodes: CategoryNode[]): NavCategory[] {
  return nodes.map((cat) => ({
    id: String(cat.id),
    label: cat.name,
    slug: cat.slug,
    // /category/:id resolves by numeric id (GET /api/categories/{id}/products).
    subs: (cat.children ?? []).map((sub) => ({ label: sub.name, href: `/category/${sub.id}` })),
  }))
}

export default function Header() {
  const location = useLocation()
  const navigate = useNavigate()
  const isLoggedIn = Boolean(getAccessToken())

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [searchValue, setSearchValue] = useState('')
  const drawerRef = useRef<HTMLDialogElement>(null)
  const hamburgerRef = useRef<HTMLButtonElement>(null)

  const queryClient = useQueryClient()
  const { data: categoryTree } = useCategoryTree()

  const { data: cartData } = useQuery({
    queryKey: ['cart', 'badge'],
    queryFn: ({ signal }) => fetchCart(signal),
    enabled: isLoggedIn,
    staleTime: 60 * 1000,
    retry: false,
  })

  const cartCount = isLoggedIn ? (cartData?.totalItemCount ?? 0) : 0

  const categories = toNavCategories(categoryTree?.categories ?? [])

  // Clear both tokens and every cached member query, then stay inside the SPA.
  const handleLogout = async () => {
    // Revoke the server-side refresh tokens first; a failure (e.g. already expired)
    // must not keep the user signed in locally.
    await apiPost('/auth/logout').catch(() => undefined)
    clearTokens()
    queryClient.clear()
    setDrawerOpen(false)
    navigate('/')
  }

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const keyword = searchValue.trim()
    if (keyword) {
      navigate(`/search?keyword=${encodeURIComponent(keyword)}`)
      setDrawerOpen(false)
    }
  }

  const closeDrawer = useCallback(() => {
    setDrawerOpen(false)
    hamburgerRef.current?.focus()
  }, [])

  // ESC closes drawer
  useEffect(() => {
    if (!drawerOpen) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeDrawer()
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [drawerOpen, closeDrawer])

  // Prevent body scroll when drawer open
  useEffect(() => {
    if (drawerOpen) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [drawerOpen])

  // A native modal keeps keyboard focus inside and makes the background inert.
  useEffect(() => {
    const drawer = drawerRef.current
    if (!drawer) return
    if (drawerOpen && !drawer.open) drawer.showModal()
    if (!drawerOpen && drawer.open) drawer.close()
  }, [drawerOpen])

  // Close drawer on route change
  useEffect(() => {
    setDrawerOpen(false)
  }, [location.pathname])

  const isTab = (path: string) => location.pathname === path

  return (
    <>
      <header className={styles.header}>
        {/* Top bar */}
        <div className={styles.topBar}>
          {/* Logo */}
          <Link to="/" className={styles.logoLink} aria-label="루네픽 홈으로">
            <span className={styles.logoText}>
              <span className={styles.logoMark} aria-hidden="true">루</span>
              <span className={styles.logoWord}>LUNEPICK</span>
            </span>
          </Link>

          {/* Search — desktop */}
          <div className={styles.searchWrap}>
            <form
              className={styles.searchForm}
              role="search"
              aria-label="상품 검색"
              onSubmit={handleSearchSubmit}
            >
              <input
                type="search"
                className={styles.searchInput}
                placeholder="브랜드, 상품, 성분을 검색해보세요"
                aria-label="검색어 입력"
                value={searchValue}
                onChange={(e) => setSearchValue(e.target.value)}
              />
              <button type="submit" className={styles.searchBtn} aria-label="검색">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <circle cx="11" cy="11" r="8" />
                  <line x1="21" y1="21" x2="16.65" y2="16.65" />
                </svg>
              </button>
            </form>
          </div>

          {/* Actions — desktop */}
          <nav className={styles.actions} aria-label="사용자 메뉴">
            {isLoggedIn ? (
              <>
                <Link to="/mypage" className={styles.iconBtn} aria-label="마이페이지">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                    <circle cx="12" cy="7" r="4" />
                  </svg>
                  <span className={styles.iconBtnLabel}>마이</span>
                </Link>
                <button type="button" className={styles.iconBtn} onClick={handleLogout} aria-label="로그아웃">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                    <polyline points="16 17 21 12 16 7" />
                    <line x1="21" y1="12" x2="9" y2="12" />
                  </svg>
                  <span className={styles.iconBtnLabel}>로그아웃</span>
                </button>
              </>
            ) : (
              <Link to="/login" className={styles.iconBtn} aria-label="로그인">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                  <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" />
                  <polyline points="10 17 15 12 10 7" />
                  <line x1="15" y1="12" x2="3" y2="12" />
                </svg>
                <span className={styles.iconBtnLabel}>로그인</span>
              </Link>
            )}
            <Link to="/cart" className={`${styles.iconBtn} ${styles.cartAction}`} aria-label={cartCount > 0 ? `장바구니 ${cartCount}개` : '장바구니'}>
              <span className={styles.iconBtnInner}>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                  <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
                  <line x1="3" y1="6" x2="21" y2="6" />
                  <path d="M16 10a4 4 0 0 1-8 0" />
                </svg>
                {cartCount > 0 && (
                  <span className={styles.cartBadge} aria-hidden="true">
                    {cartCount > 99 ? '99+' : cartCount}
                  </span>
                )}
              </span>
              <span className={styles.iconBtnLabel}>장바구니</span>
            </Link>
            <Link to="/wishlist" className={styles.iconBtn} aria-label="찜">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
              </svg>
              <span className={styles.iconBtnLabel}>찜</span>
            </Link>
          </nav>

          {/* Search entry — mobile (the desktop search field is hidden below 768px) */}
          <Link to="/search" className={styles.mobileSearch} aria-label="상품 검색">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
              <circle cx="11" cy="11" r="8" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
          </Link>

          {/* Hamburger — mobile */}
          <button
            ref={hamburgerRef}
            type="button"
            className={styles.hamburger}
            aria-label={drawerOpen ? '메뉴 닫기' : '메뉴 열기'}
            aria-expanded={drawerOpen}
            aria-controls="mobile-drawer"
            onClick={() => setDrawerOpen((prev) => !prev)}
          >
            {drawerOpen ? (
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            ) : (
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <line x1="3" y1="6" x2="21" y2="6" />
                <line x1="3" y1="12" x2="21" y2="12" />
                <line x1="3" y1="18" x2="21" y2="18" />
              </svg>
            )}
          </button>
        </div>

        {/* Category nav — desktop */}
        <nav className={styles.catNav} aria-label="카테고리 메뉴">
          <div className={styles.catNavInner}>
            {categories.map((cat) => (
              <div key={cat.id} className={styles.catItem} style={pouchStyle(cat.slug)}>
                <Link to={`/category/${cat.id}`} className={styles.catLink}>
                  <span className={styles.catSwatch} aria-hidden="true" />
                  {cat.label}
                </Link>
                {cat.subs.length > 0 && (
                  <div className={styles.mega} role="menu" aria-label={`${cat.label} 하위 메뉴`}>
                    {cat.subs.map((sub) => (
                      <Link
                        key={sub.href}
                        to={sub.href}
                        className={styles.megaLink}
                        role="menuitem"
                      >
                        {sub.label}
                      </Link>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        </nav>
      </header>

      <dialog
        id="mobile-drawer"
        ref={drawerRef}
        className={`${styles.drawer} ${drawerOpen ? styles.drawerOpen : ''}`}
        aria-label="모바일 메뉴"
        onCancel={(event) => { event.preventDefault(); closeDrawer() }}
        onClick={(event) => {
          if (event.target !== event.currentTarget) return
          const bounds = event.currentTarget.getBoundingClientRect()
          if (event.clientX < bounds.left || event.clientX > bounds.right) closeDrawer()
        }}
      >
        {/* Drawer header */}
        <div className={styles.drawerHeader}>
          <span className={styles.drawerTitle}>메뉴</span>
          <button
            type="button"
            className={styles.drawerClose}
            aria-label="메뉴 닫기"
            onClick={closeDrawer}
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>

        {/* Drawer search */}
        <div className={styles.drawerSearch}>
          <form
            role="search"
            aria-label="상품 검색"
            onSubmit={handleSearchSubmit}
            className={styles.drawerSearchForm}
          >
            <input
              type="search"
              className={styles.drawerSearchInput}
              placeholder="검색어를 입력하세요"
              aria-label="검색어 입력"
              value={searchValue}
              onChange={(e) => setSearchValue(e.target.value)}
            />
            <button type="submit" className={styles.drawerSearchBtn} aria-label="검색">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
            </button>
          </form>
        </div>

        {/* Drawer categories */}
        <nav className={styles.drawerNav} aria-label="카테고리">
          <p className={styles.drawerSectionLabel}>카테고리</p>
          {categories.map((cat) => (
            <div key={cat.id} className={styles.drawerCatGroup}>
              <Link to={`/category/${cat.id}`} className={styles.drawerCatLink}>
                {cat.label}
              </Link>
              {cat.subs.length > 0 && (
                <div className={styles.drawerSubList}>
                  {cat.subs.map((sub) => (
                    <Link key={sub.href} to={sub.href} className={styles.drawerSubLink}>
                      {sub.label}
                    </Link>
                  ))}
                </div>
              )}
            </div>
          ))}
        </nav>

        {/* Drawer member links */}
        <div className={styles.drawerMember}>
          <p className={styles.drawerSectionLabel}>회원</p>
          {isLoggedIn ? (
            <>
              <Link to="/mypage" className={styles.drawerMemberLink}>마이페이지</Link>
              <Link to="/orders" className={styles.drawerMemberLink}>주문 내역</Link>
              <Link to="/wishlist" className={styles.drawerMemberLink}>찜 목록</Link>
              <button type="button" className={styles.drawerMemberLink} onClick={handleLogout}>
                로그아웃
              </button>
            </>
          ) : (
            <>
              <Link to="/login" className={styles.drawerMemberLink}>로그인</Link>
              <Link to="/signup" className={styles.drawerMemberLink}>회원가입</Link>
            </>
          )}
        </div>
      </dialog>

      {/* Mobile bottom tab bar */}
      <nav className={styles.bottomTab} aria-label="하단 탭 메뉴">
        <Link to="/" className={`${styles.tabItem} ${isTab('/') ? styles.active : ''}`} aria-label="홈">
          <svg width="22" height="22" viewBox="0 0 24 24" fill={isTab('/') ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
            <polyline points="9 22 9 12 15 12 15 22" />
          </svg>
          <span className={styles.tabLabel}>홈</span>
        </Link>
        <Link to={categories[0] ? `/category/${categories[0].id}` : '/search'} className={`${styles.tabItem} ${location.pathname.startsWith('/category') ? styles.active : ''}`} aria-label="카테고리">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <rect x="3" y="3" width="7" height="7" rx="1" />
            <rect x="14" y="3" width="7" height="7" rx="1" />
            <rect x="3" y="14" width="7" height="7" rx="1" />
            <rect x="14" y="14" width="7" height="7" rx="1" />
          </svg>
          <span className={styles.tabLabel}>카테고리</span>
        </Link>
        <Link to="/search" className={`${styles.tabItem} ${isTab('/search') ? styles.active : ''}`} aria-label="검색">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <span className={styles.tabLabel}>검색</span>
        </Link>
        <Link to="/wishlist" className={`${styles.tabItem} ${isTab('/wishlist') ? styles.active : ''}`} aria-label="찜">
          <svg width="22" height="22" viewBox="0 0 24 24" fill={isTab('/wishlist') ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
          </svg>
          <span className={styles.tabLabel}>찜</span>
        </Link>
        <Link to="/mypage" className={`${styles.tabItem} ${isTab('/mypage') ? styles.active : ''}`} aria-label="마이">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
            <circle cx="12" cy="7" r="4" />
          </svg>
          <span className={styles.tabLabel}>마이</span>
        </Link>
      </nav>
    </>
  )
}
