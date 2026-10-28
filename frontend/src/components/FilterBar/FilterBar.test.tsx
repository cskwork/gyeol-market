import { afterEach, describe, expect, it, vi } from 'vitest'
import FilterBar from './FilterBar'
import { byText, click, render, type Rendered } from '@/testing/render'

describe('FilterBar', () => {
  let view: Rendered | undefined
  afterEach(() => view?.unmount())

  it('omits the filter button when the page has no filter sheet (category lists sort only)', () => {
    view = render(<FilterBar sort="POPULAR" onSortChange={() => {}} />)
    expect(byText(view.container, 'button', '필터')).toBeNull()
    expect(view.container.querySelector('select[aria-label="정렬 기준 선택"]')).not.toBeNull()
  })

  it('renders a working filter button when a handler is given', () => {
    const onFilterClick = vi.fn()
    view = render(<FilterBar sort="POPULAR" onSortChange={() => {}} onFilterClick={onFilterClick} />)
    click(byText(view.container, 'button', '필터'))
    expect(onFilterClick).toHaveBeenCalledTimes(1)
  })
})
