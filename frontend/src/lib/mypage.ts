import { apiGet } from './api'
import type { MySummary } from './types'

/**
 * GET /api/me/summary — returns point balance, coupon count, order count, and grade name.
 */
export function getMySummary(signal?: AbortSignal): Promise<MySummary> {
  return apiGet<MySummary>('/me/summary', signal)
}
