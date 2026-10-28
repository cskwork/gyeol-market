import { lazy, Suspense } from 'react'
import { Routes, Route } from 'react-router-dom'
import Layout from './components/layout/Layout'
import Home from './pages/Home'
import Login from './pages/auth/Login'
import Signup from './pages/auth/Signup'
import ProductDetail from './pages/ProductDetail'
import Cart from './pages/Cart'
import Checkout from './pages/Checkout'
import OrderComplete from './pages/OrderComplete'
import Search from './pages/Search'
import ProductList from './pages/ProductList'
import MyPage from './pages/MyPage'
import OrderHistory from './pages/OrderHistory'
import Wishlist from './pages/Wishlist'
import NotFound from './pages/NotFound'
import DemoNotice from './components/DemoNotice/DemoNotice'
import { IS_DEMO } from './lib/demo'

// Dev page is code-split and excluded from production bundles by Vite's
// dead-code elimination on the `import.meta.env.DEV` constant.
const LazyDev = lazy(() => import('./pages/Dev'))

export default function App() {
  return (
    <>
    {IS_DEMO && <DemoNotice />}
    <Routes>
      {/* Auth pages: no shared chrome (full-page layouts) */}
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<Signup />} />

      {/* Shell: Header + Outlet + Footer */}
      <Route element={<Layout />}>
        <Route path="/" element={<Home />} />
        <Route path="/products/:id" element={<ProductDetail />} />
        <Route path="/cart" element={<Cart />} />
        <Route path="/checkout" element={<Checkout />} />
        <Route path="/order/complete" element={<OrderComplete />} />
        <Route path="/orders/:orderNo" element={<OrderComplete />} />
        <Route path="/search" element={<Search />} />
        <Route path="/category/:id" element={<ProductList />} />
        <Route path="/mypage" element={<MyPage />} />
        <Route path="/orders" element={<OrderHistory />} />
        <Route path="/wishlist" element={<Wishlist />} />
        {import.meta.env.DEV && (
          <Route
            path="/dev"
            element={
              <Suspense fallback={null}>
                <LazyDev />
              </Suspense>
            }
          />
        )}
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
    </>
  )
}
