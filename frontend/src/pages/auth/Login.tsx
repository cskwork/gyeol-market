import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { apiPost, setAccessToken, setRefreshToken, ApiError } from '@/lib/api'
import { mergeAnonymousCart } from '@/lib/cart'
import type { LoginRequest, LoginResponse } from '@/lib/types'
import { IS_DEMO } from '@/lib/demo'
import { DEMO_ACCOUNT } from '@/demo/fixtures'
import styles from './auth.module.css'

/** Key used by the anonymous cart session on this device. */
const ANON_SESSION_KEY = 'olive.sessionId'

function getOrCreateSessionId(): string {
  let id = localStorage.getItem(ANON_SESSION_KEY)
  if (!id) {
    id = crypto.randomUUID()
    localStorage.setItem(ANON_SESSION_KEY, id)
  }
  return id
}

interface FormErrors {
  email?: string
  password?: string
}

function validate(email: string, password: string): FormErrors {
  const errs: FormErrors = {}
  if (!email) errs.email = '이메일을 입력해주세요.'
  else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) errs.email = '이메일 형식이 올바르지 않습니다.'
  if (!password) errs.password = '비밀번호를 입력해주세요.'
  return errs
}

export default function Login() {
  const navigate = useNavigate()
  const location = useLocation()
  // Pages that bounce anonymous users here pass { from }; send them back after login.
  const navState = location.state as { from?: string; signedUp?: boolean; email?: string } | null
  const from = navState?.from ?? '/'
  // Signup redirects here with { signedUp, email } so the new member can log straight in.
  const signedUp = navState?.signedUp === true
  const signedUpEmail = signedUp && typeof navState?.email === 'string' ? navState.email : null
  const [email, setEmail] = useState(signedUpEmail ?? (IS_DEMO ? DEMO_ACCOUNT.email : ''))
  const [password, setPassword] = useState(IS_DEMO && !signedUpEmail ? DEMO_ACCOUNT.password : '')
  const [errors, setErrors] = useState<FormErrors>({})
  const [globalError, setGlobalError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setGlobalError(null)

    const errs = validate(email, password)
    if (Object.keys(errs).length > 0) {
      setErrors(errs)
      return
    }
    setErrors({})

    setSubmitting(true)
    try {
      const res = await apiPost<LoginResponse>('/auth/login', {
        email,
        password,
      } satisfies LoginRequest)
      setAccessToken(res.accessToken)
      setRefreshToken(res.refreshToken)

      // Merge any anonymous cart items into the member cart. Fire-and-forget:
      // a merge failure should not block the user from navigating home.
      const sessionId = getOrCreateSessionId()
      mergeAnonymousCart(sessionId).catch(() => {
        // Merge failure is non-fatal — the user still logged in successfully.
      })

      navigate(from, { replace: true })
    } catch (err) {
      if (err instanceof ApiError) {
        setGlobalError(err.message)
      } else {
        setGlobalError('로그인 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <Link to="/" className={styles.logo} aria-label="루네픽 홈으로">
          <span className={styles.logoMark} aria-hidden="true">루</span>
          <span className={styles.logoText}>LUNEPICK</span>
        </Link>

        <h1 className={styles.title}>로그인</h1>

        {IS_DEMO && !signedUpEmail && (
          <p className={styles.demoHint}>
            데모 계정이 입력되어 있습니다. 그대로 로그인하거나 아무 이메일로 새로 시작할 수 있습니다.
          </p>
        )}

        <form className={styles.form} onSubmit={handleSubmit} noValidate>
          {signedUp && !globalError && (
            <div className={styles.successNotice} role="status">
              회원가입이 완료되었습니다. 로그인해주세요.
            </div>
          )}
          {globalError && (
            <div className={styles.globalError} role="alert">
              {globalError}
            </div>
          )}

          <div className={styles.field}>
            <label htmlFor="email" className={styles.label}>이메일</label>
            <input
              id="email"
              type="email"
              className={`${styles.input} ${errors.email ? styles.error : ''}`}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="example@email.com"
              autoComplete="email"
              aria-invalid={Boolean(errors.email)}
              aria-describedby={errors.email ? 'email-error' : undefined}
            />
            {errors.email && (
              <span id="email-error" className={styles.fieldError} role="alert">{errors.email}</span>
            )}
          </div>

          <div className={styles.field}>
            <label htmlFor="password" className={styles.label}>비밀번호</label>
            <input
              id="password"
              type="password"
              className={`${styles.input} ${errors.password ? styles.error : ''}`}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="비밀번호 입력"
              autoComplete="current-password"
              aria-invalid={Boolean(errors.password)}
              aria-describedby={errors.password ? 'password-error' : undefined}
            />
            {errors.password && (
              <span id="password-error" className={styles.fieldError} role="alert">{errors.password}</span>
            )}
          </div>

          <button
            type="submit"
            className={styles.submitBtn}
            disabled={submitting}
            aria-busy={submitting}
          >
            {submitting ? '로그인 중…' : '로그인'}
          </button>
        </form>

        <p className={styles.footer}>
          아직 회원이 아니신가요?{' '}
          <Link to="/signup" className={styles.footerLink}>회원가입</Link>
        </p>
      </div>
    </div>
  )
}
