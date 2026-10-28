import { apiGet, apiGetPage } from './api'
import type {
  AutocompleteResponse,
  PopularResponse,
  ProductListItem,
  SearchProductsParams,
  PageMeta,
} from './types'

/**
 * GET /api/search/products — keyword/category/brand search with pagination.
 */
export function searchProducts(
  params: SearchProductsParams,
  signal?: AbortSignal,
): Promise<{ data: ProductListItem[]; meta: PageMeta | undefined }> {
  const qs = new URLSearchParams()
  if (params.keyword) qs.set('keyword', params.keyword)
  if (params.categoryId !== undefined) qs.set('categoryId', String(params.categoryId))
  if (params.brandId !== undefined) qs.set('brandId', String(params.brandId))
  if (params.sort) qs.set('sort', params.sort)
  qs.set('page', String(params.page ?? 0))
  qs.set('size', String(params.size ?? 20))
  return apiGetPage<ProductListItem[]>(`/search/products?${qs.toString()}`, signal)
}

/**
 * GET /api/search/autocomplete?prefix= — prefix-based keyword suggestions.
 */
export function autocomplete(
  prefix: string,
  size = 10,
  signal?: AbortSignal,
): Promise<AutocompleteResponse> {
  const qs = new URLSearchParams({ prefix, size: String(size) })
  return apiGet<AutocompleteResponse>(`/search/autocomplete?${qs.toString()}`, signal)
}

/**
 * GET /api/search/popular — top popular search keywords from the past hour.
 */
export function popularKeywords(size = 10, signal?: AbortSignal): Promise<PopularResponse> {
  const qs = new URLSearchParams({ size: String(size) })
  return apiGet<PopularResponse>(`/search/popular?${qs.toString()}`, signal)
}
