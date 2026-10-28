import { apiGet, apiPost, apiPatch, apiDelete } from './api'
import type {
  CartResponse,
  AddToCartRequest,
  AddToCartResponse,
  UpdateCartQuantityRequest,
  CartMergeResponse,
} from './types'

export function fetchCart(signal?: AbortSignal): Promise<CartResponse> {
  return apiGet<CartResponse>('/cart', signal)
}

export function addToCart(body: AddToCartRequest): Promise<AddToCartResponse> {
  return apiPost<AddToCartResponse>('/cart/items', body)
}

export function updateCartItemQty(
  cartItemId: number,
  body: UpdateCartQuantityRequest,
): Promise<null> {
  return apiPatch<null>(`/cart/items/${cartItemId}`, body)
}

export function removeCartItem(cartItemId: number): Promise<null> {
  return apiDelete<null>(`/cart/items/${cartItemId}`)
}

/**
 * Merges the anonymous (session-based) cart into the authenticated member cart.
 * Call this after a successful login. The sessionId should match what was sent
 * in X-Session-ID headers during anonymous browsing.
 *
 * POST /api/cart/merge { sessionId }
 */
export function mergeAnonymousCart(sessionId: string): Promise<CartMergeResponse> {
  return apiPost<CartMergeResponse>('/cart/merge', { sessionId })
}
