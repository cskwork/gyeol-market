import { useEffect } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import Header from './Header'
import Footer from './Footer'
import styles from './Layout.module.css'

export default function Layout() {
  const { pathname } = useLocation()
  // A new page starts at the top (SPA navigation otherwise keeps the old scroll offset).
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [pathname])

  return (
    <>
      <a href="#main-content" className="skip-link">본문으로 바로가기</a>
      <Header />
      <main id="main-content" tabIndex={-1} className={styles.main}>
        <Outlet />
      </main>
      <Footer />
    </>
  )
}
