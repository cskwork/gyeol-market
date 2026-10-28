import { Link } from 'react-router-dom'
import styles from './Footer.module.css'

const REPO_URL = 'https://github.com/cskwork/olive-clone'

const SHOP_LINKS = [
  { label: '전체 상품 검색', to: '/search' },
  { label: '장바구니', to: '/cart' },
  { label: '주문 내역', to: '/orders' },
  { label: '마이페이지', to: '/mypage' },
]

const PROJECT_LINKS = [
  { label: '소스 코드 (GitHub)', href: REPO_URL },
  { label: '아키텍처 문서', href: `${REPO_URL}/blob/main/docs/ARCHITECTURE.md` },
  { label: 'API 개요', href: `${REPO_URL}/blob/main/docs/API_OVERVIEW.md` },
]

export default function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={styles.inner}>
        <div className={styles.columns}>
          <nav className={styles.col} aria-label="쇼핑">
            <p className={styles.colTitle}>쇼핑</p>
            {SHOP_LINKS.map((link) => (
              <Link key={link.to} to={link.to} className={styles.colLink}>
                {link.label}
              </Link>
            ))}
          </nav>
          <nav className={styles.col} aria-label="프로젝트">
            <p className={styles.colTitle}>프로젝트</p>
            {PROJECT_LINKS.map((link) => (
              <a
                key={link.href}
                href={link.href}
                className={styles.colLink}
                target="_blank"
                rel="noopener noreferrer"
              >
                {link.label}
              </a>
            ))}
          </nav>
        </div>

        <div className={styles.company}>
          <p className={styles.brand}>
            <span className={styles.brandMark} aria-hidden="true">루</span>
            LUNEPICK
          </p>
          <p className={styles.info}>
            루네픽은 뷰티·라이프스타일을 둘러보는 포트폴리오용 데모 스토어입니다. 실제 판매·배송·결제는 이루어지지 않으며,
            상품 이미지는 생성된 샘플이고 어떤 유통사·브랜드와도 제휴 관계가 없습니다.
          </p>
          <p className={styles.copyright}>
            &copy; {new Date().getFullYear()} LUNEPICK demo · MIT License
          </p>
        </div>
      </div>
    </footer>
  )
}
