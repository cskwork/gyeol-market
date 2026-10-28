import type { SortOption } from '@/lib/types'
import styles from './FilterBar.module.css'

export interface AppliedFilter {
  id: string
  label: string
}

interface FilterBarProps {
  sort: SortOption
  onSortChange: (s: SortOption) => void
  appliedFilters?: AppliedFilter[]
  onRemoveFilter?: (id: string) => void
  onFilterClick?: () => void
}

const SORT_OPTIONS: { value: SortOption; label: string }[] = [
  { value: 'POPULAR', label: '인기순' },
  { value: 'LATEST', label: '신상품순' },
  { value: 'PRICE_ASC', label: '낮은가격순' },
  { value: 'PRICE_DESC', label: '높은가격순' },
  { value: 'RATING', label: '리뷰순' },
]

export default function FilterBar({
  sort,
  onSortChange,
  appliedFilters = [],
  onRemoveFilter,
  onFilterClick,
}: FilterBarProps) {
  return (
    <div className={styles.bar} role="toolbar" aria-label="정렬 및 필터">
      {/* Sort */}
      <div className={styles.sortWrap}>
        <select
          className={styles.sortSelect}
          value={sort}
          onChange={(e) => onSortChange(e.target.value as SortOption)}
          aria-label="정렬 기준 선택"
        >
          {SORT_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        <span className={styles.sortChevron} aria-hidden="true">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </span>
      </div>

      {/* Filter button */}
      <button
        type="button"
        className={styles.filterBtn}
        onClick={onFilterClick}
        aria-label={`필터${appliedFilters.length > 0 ? ` (${appliedFilters.length}개 적용됨)` : ''}`}
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <line x1="4" y1="6" x2="20" y2="6" />
          <line x1="8" y1="12" x2="16" y2="12" />
          <line x1="11" y1="18" x2="13" y2="18" />
        </svg>
        필터
        {appliedFilters.length > 0 && (
          <span className={styles.filterBadge} aria-hidden="true">
            {appliedFilters.length}
          </span>
        )}
      </button>

      {appliedFilters.length > 0 && (
        <>
          <div className={styles.divider} aria-hidden="true" />
          <div className={styles.pills} aria-label="적용된 필터">
            {appliedFilters.map((f) => (
              <span key={f.id} className={styles.pill}>
                {f.label}
                {onRemoveFilter && (
                  <button
                    type="button"
                    className={styles.pillRemove}
                    onClick={() => onRemoveFilter(f.id)}
                    aria-label={`${f.label} 필터 제거`}
                  >
                    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" aria-hidden="true">
                      <line x1="18" y1="6" x2="6" y2="18" />
                      <line x1="6" y1="6" x2="18" y2="18" />
                    </svg>
                  </button>
                )}
              </span>
            ))}
          </div>
        </>
      )}
    </div>
  )
}
