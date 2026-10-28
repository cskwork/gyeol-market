import { apiDelete, apiGetPage, apiPost } from './api'
import type { PageMeta, WishlistItem } from './types'

/**
 * GET /api/me/wishlist?page=&size= — paginated wishlist for the current member.
 */
export function listWishlist(
  params: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<{ data: WishlistItem[]; meta: PageMeta | undefined }> {
  const qs = new URLSearchParams()
  qs.set('page', String(params.page ?? 0))
  qs.set('size', String(params.size ?? 20))
  return apiGetPage<WishlistItem[]>(`/me/wishlist?${qs.toString()}`, signal)
}

/**
 * POST /api/me/wishlist { productId } — add a product to the wishlist (idempotent).
 */
export function addWishlist(productId: number): Promise<null> {
  return apiPost<null>('/me/wishlist', { productId })
}

/**
 * DELETE /api/me/wishlist/{productId} — remove a product from the wishlist.
 */
export function removeWishlist(productId: number): Promise<null> {
  return apiDelete<null>(`/me/wishlist/${productId}`)
}
