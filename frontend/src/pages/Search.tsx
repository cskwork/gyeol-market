import { useState, useEffect, useRef, useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery, useInfiniteQuery } from '@tanstack/react-query'
import { searchProducts, autocomplete, popularKeywords } from '@/lib/search'
import { apiGetPage } from '@/lib/api'
import type { SearchSortOption, ProductListItem, PageMeta } from '@/lib/types'
import ProductCard from '@/components/ProductCard/ProductCard'
import styles from './Search.module.css'

const SEARCH_SORT_OPTIONS: { value: SearchSortOption; label: string }[] = [
  { value: 'RELEVANCE', label: '관련도순' },
  { value: 'POPULAR', label: '인기순' },
  { value: 'LATEST', label: '신상품순' },
  { value: 'PRICE_ASC', label: '낮은가격순' },
  { value: 'PRICE_DESC', label: '높은가격순' },
  { value: 'RATING', label: '리뷰순' },
]

const PAGE_SIZE = 20

function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState<T>(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return debounced
}

interface SearchPage {
  data: ProductListItem[]
  meta: PageMeta | undefined
}

export default function Search() {
  const [searchParams, setSearchParams] = useSearchParams()

  const urlKeyword = searchParams.get('keyword') ?? ''
  const [inputValue, setInputValue] = useState(urlKeyword)
  const sortParam = searchParams.get('sort')
  const sort: SearchSortOption = SEARCH_SORT_OPTIONS.some((option) => option.value === sortParam)
    ? sortParam as SearchSortOption : 'RELEVANCE'
  const [showSuggestions, setShowSuggestions] = useState(false)
  const [activeSuggestionIndex, setActiveSuggestionIndex] = useState(-1)

  const inputRef = useRef<HTMLInputElement>(null)
  const suggestionsRef = useRef<HTMLUListElement>(null)
  const formRef = useRef<HTMLFormElement>(null)

  const debouncedInput = useDebounce(inputValue, 280)

  // Sync input when URL keyword changes (browser back/forward)
  useEffect(() => {
    setInputValue(urlKeyword)
  }, [urlKeyword])

  // Popular keywords (shown when no keyword)
  const { data: popularData } = useQuery({
    queryKey: ['search', 'popular'],
    queryFn: ({ signal }) => popularKeywords(10, signal),
    staleTime: 5 * 60 * 1000,
  })

  // Autocomplete suggestions
  const { data: autocompleteData } = useQuery({
    queryKey: ['search', 'autocomplete', debouncedInput],
    queryFn: ({ signal }) => autocomplete(debouncedInput, 8, signal),
    enabled: debouncedInput.length >= 1 && showSuggestions,
    staleTime: 30_000,
  })

  const suggestions = autocompleteData?.suggestions ?? []

  // Product search with infinite scroll / load-more
  const {
    data: searchData,
    isLoading: searchLoading,
    isError: searchError,
    error: searchErrorObj,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    refetch,
  } = useInfiniteQuery<SearchPage, Error>({
    queryKey: ['search', 'products', urlKeyword, sort],
    queryFn: ({ pageParam, signal }) => urlKeyword ? searchProducts(
        { keyword: urlKeyword, sort, page: pageParam as number, size: PAGE_SIZE },
        signal,
      ) : apiGetPage<ProductListItem[]>(`/products?sort=${sort === 'RELEVANCE' ? 'LATEST' : sort}&page=${pageParam}&size=${PAGE_SIZE}`, signal),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => {
      if (!lastPage.meta) return undefined
      const { page, size, total } = lastPage.meta
      return (page + 1) * size < total ? page + 1 : undefined
    },
  })

  const allProducts = searchData?.pages.flatMap((p) => p.data) ?? []
  const totalCount = searchData?.pages[0]?.meta?.total ?? 0

  const submitSearch = useCallback(
    (keyword: string) => {
      const trimmed = keyword.trim()
      setShowSuggestions(false)
      setActiveSuggestionIndex(-1)
      if (trimmed) {
        setSearchParams({ keyword: trimmed, sort }, { replace: false })
      } else {
        setSearchParams({ sort }, { replace: false })
      }
    },
    [setSearchParams, sort],
  )

  const handleFormSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    submitSearch(inputValue)
    inputRef.current?.blur()
  }

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setInputValue(e.target.value)
    setActiveSuggestionIndex(-1)
    setShowSuggestions(true)
  }

  const handleInputFocus = () => {
    if (inputValue.length > 0) setShowSuggestions(true)
  }

  const handleInputBlur = () => {
    // Delay so click on suggestion registers first
    setTimeout(() => setShowSuggestions(false), 150)
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (!showSuggestions || suggestions.length === 0) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActiveSuggestionIndex((prev) => Math.min(prev + 1, suggestions.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveSuggestionIndex((prev) => Math.max(prev - 1, -1))
    } else if (e.key === 'Enter' && activeSuggestionIndex >= 0) {
      e.preventDefault()
      const selected = suggestions[activeSuggestionIndex]
      setInputValue(selected)
      submitSearch(selected)
    } else if (e.key === 'Escape') {
      setShowSuggestions(false)
      setActiveSuggestionIndex(-1)
    }
  }

  const handleSuggestionClick = (suggestion: string) => {
    setInputValue(suggestion)
    submitSearch(suggestion)
    inputRef.current?.focus()
  }

  const handlePopularKeywordClick = (keyword: string) => {
    setInputValue(keyword)
    submitSearch(keyword)
  }

  const handleSortChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const params = new URLSearchParams(searchParams)
    params.set('sort', e.target.value)
    setSearchParams(params)
  }

  const handleLoadMore = () => {
    fetchNextPage()
  }

  const handleRetry = () => {
    refetch()
  }

  const isEmptySearch = urlKeyword.length === 0
  const hasResults = allProducts.length > 0

  return (
    <div className={styles.page}>
      <div className="app-container">
        <h1 className="visually-hidden">상품 검색과 전체 상품</h1>
        {/* Search bar */}
        <div className={styles.searchBarSection}>
          <form
            ref={formRef}
            onSubmit={handleFormSubmit}
            className={styles.searchForm}
            role="search"
          >
            <div className={styles.searchInputWrap}>
              <label htmlFor="search-input" className="visually-hidden">
                상품 검색
              </label>
              <input
                id="search-input"
                ref={inputRef}
                type="search"
                role="combobox"
                aria-expanded={showSuggestions && suggestions.length > 0}
                className={styles.searchInput}
                value={inputValue}
                onChange={handleInputChange}
                onFocus={handleInputFocus}
                onBlur={handleInputBlur}
                onKeyDown={handleKeyDown}
                placeholder="브랜드, 상품명 검색"
                autoComplete="off"
                autoCorrect="off"
                spellCheck={false}
                aria-autocomplete="list"
                aria-controls={showSuggestions && suggestions.length > 0 ? 'search-suggestions' : undefined}
                aria-activedescendant={
                  activeSuggestionIndex >= 0
                    ? `suggestion-${activeSuggestionIndex}`
                    : undefined
                }
              />
              {inputValue && (
                <button
                  type="button"
                  className={styles.clearBtn}
                  onClick={() => {
                    setInputValue('')
                    submitSearch('')
                    setShowSuggestions(false)
                    inputRef.current?.focus()
                  }}
                  aria-label="검색어 지우기"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" aria-hidden="true">
                    <line x1="18" y1="6" x2="6" y2="18" />
                    <line x1="6" y1="6" x2="18" y2="18" />
                  </svg>
                </button>
              )}
              <button
                type="submit"
                className={styles.searchBtn}
                aria-label="검색"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <circle cx="11" cy="11" r="8" />
                  <line x1="21" y1="21" x2="16.65" y2="16.65" />
                </svg>
              </button>
            </div>

            {/* Autocomplete suggestions */}
            {showSuggestions && suggestions.length > 0 && (
              <ul
                id="search-suggestions"
                ref={suggestionsRef}
                className={styles.suggestions}
                role="listbox"
                aria-label="검색 추천어"
              >
                {suggestions.map((s, idx) => (
                  <li
                    key={s}
                    id={`suggestion-${idx}`}
                    className={`${styles.suggestionItem} ${activeSuggestionIndex === idx ? styles.suggestionActive : ''}`}
                    role="option"
                    aria-selected={activeSuggestionIndex === idx}
                    onMouseDown={() => handleSuggestionClick(s)}
                  >
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true" className={styles.suggestionIcon}>
                      <circle cx="11" cy="11" r="8" />
                      <line x1="21" y1="21" x2="16.65" y2="16.65" />
                    </svg>
                    {s}
                  </li>
                ))}
              </ul>
            )}
          </form>
        </div>

        {/* No keyword: popular keywords */}
        {isEmptySearch && (
          <section className={styles.popularSection} aria-label="인기 검색어">
            <h2 className={styles.popularTitle}>인기 검색어</h2>
            {popularData?.keywords && popularData.keywords.length > 0 ? (
              <ol className={styles.popularList} aria-label="인기 검색어 목록">
                {popularData.keywords.map((item) => (
                  <li key={item.keyword}>
                    <button
                      type="button"
                      className={styles.popularChip}
                      onClick={() => handlePopularKeywordClick(item.keyword)}
                    >
                      <span className={styles.popularRank} aria-label={`${item.rank}위`}>
                        {item.rank}
                      </span>
                      <span className={styles.popularKeyword}>{item.keyword}</span>
                    </button>
                  </li>
                ))}
              </ol>
            ) : (
              <p className={styles.popularEmpty}>인기 검색어 데이터를 불러오는 중입니다.</p>
            )}
          </section>
        )}

        {/* Search results */}
          <section className={styles.resultsSection} aria-label={isEmptySearch ? '전체 상품' : `"${urlKeyword}" 검색 결과`}>
            {/* Results header + sort */}
            {!searchLoading && hasResults && (
              <div className={styles.resultsHeader}>
                <p className={styles.resultCount} role="status">
                  {isEmptySearch ? '전체 상품 ' : <><span className={styles.keyword}>{urlKeyword}</span>{' '}검색 결과{' '}</>}
                  <strong>{totalCount.toLocaleString('ko-KR')}</strong>개
                </p>
                <div className={styles.sortWrap}>
                  <select
                    className={styles.sortSelect}
                    value={sort}
                    onChange={handleSortChange}
                    aria-label="정렬 기준 선택"
                  >
                    {SEARCH_SORT_OPTIONS.map((opt) => (
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
              </div>
            )}

            {/* Loading skeleton */}
            {searchLoading && (
              <div
                className={styles.grid}
                aria-busy="true"
                aria-label="검색 결과 로딩 중"
              >
                {Array.from({ length: 10 }, (_, i) => (
                  <div key={i} className={`${styles.skeletonCard} skeleton-shimmer`} aria-hidden="true" />
                ))}
              </div>
            )}

            {/* Error state */}
            {searchError && !searchLoading && (
              <div className="error-state" role="alert">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
                <p>검색 결과를 불러오지 못했습니다.</p>
                <p className={styles.errorDetail}>{(searchErrorObj as Error).message}</p>
                <button
                  type="button"
                  className={styles.retryBtn}
                  onClick={handleRetry}
                >
                  다시 시도
                </button>
              </div>
            )}

            {/* Empty state */}
            {!searchLoading && !searchError && !hasResults && urlKeyword && (
              <div className="empty-state">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden="true">
                  <circle cx="11" cy="11" r="8" />
                  <line x1="21" y1="21" x2="16.65" y2="16.65" />
                </svg>
                <p className="empty-state__title">검색 결과가 없습니다</p>
                <p>
                  <strong>{urlKeyword}</strong>에 해당하는 상품을 찾을 수 없습니다.
                  <br />
                  다른 검색어로 다시 시도해 보세요.
                </p>
                <button type="button" className={styles.retryBtn} onClick={() => { setInputValue(''); submitSearch('') }}>전체 상품 보기</button>
              </div>
            )}

            {/* Product grid */}
            {!searchLoading && hasResults && (
              <ul className={styles.grid} aria-label="검색된 상품 목록">
                {allProducts.map((product) => (
                  <li key={product.productId}>
                    <ProductCard product={product} />
                  </li>
                ))}
                {/* Skeleton cards while fetching next page */}
                {isFetchingNextPage &&
                  Array.from({ length: 4 }, (_, i) => (
                    <li key={`skeleton-next-${i}`} aria-hidden="true">
                      <div className={`${styles.skeletonCard} skeleton-shimmer`} />
                    </li>
                  ))}
              </ul>
            )}

            {/* Load more */}
            {!searchLoading && !searchError && hasNextPage && (
              <div className={styles.loadMoreWrap}>
                <button
                  type="button"
                  className={styles.loadMoreBtn}
                  onClick={handleLoadMore}
                  disabled={isFetchingNextPage}
                  aria-label="상품 더 보기"
                >
                  {isFetchingNextPage ? '로딩 중...' : '더 보기'}
                </button>
              </div>
            )}
          </section>
      </div>
    </div>
  )
}
