import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { apiPost, ApiError } from '@/lib/api'
import type { SignupRequest, SignupResponse } from '@/lib/types'
import styles from './auth.module.css'

interface FormErrors {
  email?: string
  password?: string
  name?: string
  phone?: string
}

function validate(fields: {
  email: string
  password: string
  name: string
  phone: string
}): FormErrors {
  const errs: FormErrors = {}
  if (!fields.email) errs.email = '이메일을 입력해주세요.'
  else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(fields.email))
    errs.email = '이메일 형식이 올바르지 않습니다.'
  if (!fields.password) errs.password = '비밀번호를 입력해주세요.'
  else if (fields.password.length < 8) errs.password = '비밀번호는 8자 이상이어야 합니다.'
  if (!fields.name) errs.name = '이름을 입력해주세요.'
  if (fields.phone && !/^01[016789]-?\d{3,4}-?\d{4}$/.test(fields.phone))
    errs.phone = '휴대전화 형식이 올바르지 않습니다. (예: 010-1234-5678)'
  return errs
}

export default function Signup() {
  const navigate = useNavigate()
  const [form, setForm] = useState({ email: '', password: '', name: '', phone: '' })
  const [errors, setErrors] = useState<FormErrors>({})
  const [globalError, setGlobalError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const set = (field: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setGlobalError(null)

    const errs = validate(form)
    if (Object.keys(errs).length > 0) {
      setErrors(errs)
      return
    }
    setErrors({})

    setSubmitting(true)
    try {
      const body: SignupRequest = {
        email: form.email,
        password: form.password,
        name: form.name,
        ...(form.phone ? { phone: form.phone } : {}),
      }
      await apiPost<SignupResponse>('/auth/signup', body)
      navigate('/login', { state: { signedUp: true, email: form.email } })
    } catch (err) {
      if (err instanceof ApiError) {
        setGlobalError(err.message)
      } else {
        setGlobalError('회원가입 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
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

        <h1 className={styles.title}>회원가입</h1>

        <form className={styles.form} onSubmit={handleSubmit} noValidate>
          {globalError && (
            <div className={styles.globalError} role="alert">
              {globalError}
            </div>
          )}

          <div className={styles.field}>
            <label htmlFor="email" className={styles.label}>이메일 *</label>
            <input
              id="email"
              type="email"
              className={`${styles.input} ${errors.email ? styles.error : ''}`}
              value={form.email}
              onChange={set('email')}
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
            <label htmlFor="password" className={styles.label}>비밀번호 *</label>
            <input
              id="password"
              type="password"
              className={`${styles.input} ${errors.password ? styles.error : ''}`}
              value={form.password}
              onChange={set('password')}
              placeholder="8자 이상 입력"
              autoComplete="new-password"
              aria-invalid={Boolean(errors.password)}
              aria-describedby={errors.password ? 'password-error' : 'password-hint'}
            />
            {errors.password ? (
              <span id="password-error" className={styles.fieldError} role="alert">{errors.password}</span>
            ) : (
              <span id="password-hint" className={styles.hint}>영문·숫자·특수문자 조합 8자 이상 권장</span>
            )}
          </div>

          <div className={styles.field}>
            <label htmlFor="name" className={styles.label}>이름 *</label>
            <input
              id="name"
              type="text"
              className={`${styles.input} ${errors.name ? styles.error : ''}`}
              value={form.name}
              onChange={set('name')}
              placeholder="이름 입력"
              autoComplete="name"
              aria-invalid={Boolean(errors.name)}
              aria-describedby={errors.name ? 'name-error' : undefined}
            />
            {errors.name && (
              <span id="name-error" className={styles.fieldError} role="alert">{errors.name}</span>
            )}
          </div>

          <div className={styles.field}>
            <label htmlFor="phone" className={styles.label}>휴대전화 (선택)</label>
            <input
              id="phone"
              type="tel"
              className={`${styles.input} ${errors.phone ? styles.error : ''}`}
              value={form.phone}
              onChange={set('phone')}
              placeholder="010-1234-5678"
              autoComplete="tel"
              aria-invalid={Boolean(errors.phone)}
              aria-describedby={errors.phone ? 'phone-error' : undefined}
            />
            {errors.phone && (
              <span id="phone-error" className={styles.fieldError} role="alert">{errors.phone}</span>
            )}
          </div>

          <button
            type="submit"
            className={styles.submitBtn}
            disabled={submitting}
            aria-busy={submitting}
          >
            {submitting ? '처리 중…' : '회원가입'}
          </button>
        </form>

        <p className={styles.footer}>
          이미 회원이신가요?{' '}
          <Link to="/login" className={styles.footerLink}>로그인</Link>
        </p>
      </div>
    </div>
  )
}
