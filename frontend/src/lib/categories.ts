import { useQuery } from '@tanstack/react-query'
import { apiGet } from './api'
import type { CSSProperties } from 'react'

// GET /api/categories -> CategoryDtos.PublicTreeResponse
export interface CategoryNode {
  id: number
  name: string
  slug: string
  children: CategoryNode[]
}

export interface CategoryTree {
  categories: CategoryNode[]
}

export function fetchCategoryTree(signal?: AbortSignal): Promise<CategoryTree> {
  return apiGet<CategoryTree>('/categories', signal)
}

/** Shared, long-lived category tree query (header, home pouches, drawer). */
export function useCategoryTree() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: ({ signal }) => fetchCategoryTree(signal),
    staleTime: 5 * 60 * 1000,
  })
}

// Each root category owns one pouch color; unknown slugs fall back to violet.
const POUCH_BY_SLUG: Record<string, string> = {
  skincare: 'aqua',
  makeup: 'rose',
  'hair-body': 'marigold',
}

export function pouchName(slug: string | undefined): string {
  return (slug && POUCH_BY_SLUG[slug]) || 'violet'
}

/** CSS custom properties `--pouch` / `--pouch-deep` for a category. */
export function pouchStyle(slug: string | undefined): CSSProperties {
  const name = pouchName(slug)
  return {
    ['--pouch' as string]: `var(--pouch-${name})`,
    ['--pouch-deep' as string]: `var(--pouch-${name}-deep)`,
  }
}
