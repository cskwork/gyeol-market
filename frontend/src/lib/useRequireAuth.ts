import { useEffect } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { getAccessToken } from './api'

/**
 * Redirects to /login if there is no access token.
 * Preserves the intended destination via location state.
 */
export function useRequireAuth(): void {
  const navigate = useNavigate()
  const location = useLocation()

  useEffect(() => {
    if (!getAccessToken()) {
      navigate('/login', { state: { from: location.pathname }, replace: true })
    }
  }, [navigate, location.pathname])
}
