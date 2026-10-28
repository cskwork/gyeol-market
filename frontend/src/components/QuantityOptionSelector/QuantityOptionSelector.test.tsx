import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import QuantityOptionSelector, { type SelectedOption } from './QuantityOptionSelector'
import type { ProductOptionSummary } from '@/lib/types'
import { byText, changeValue, click, render, type Rendered } from '@/testing/render'

const OPTIONS: ProductOptionSummary[] = [
  { optionId: 1, optionName: '50ml', optionPrice: 0, status: 'ON_SALE', availableQuantity: null },
  { optionId: 2, optionName: '100ml', optionPrice: 5000, status: 'ON_SALE', availableQuantity: null },
]

// Stands in for ProductDetailView: the parent stores the selection in its own state.
function Parent({ onSelected }: { onSelected: (s: SelectedOption[]) => void }) {
  const [selected, setSelected] = useState<SelectedOption[]>([])
  return (
    <>
      <QuantityOptionSelector
        options={OPTIONS}
        unitPrice={20000}
        onChange={(next) => { setSelected(next); onSelected(next) }}
      />
      <output>{selected.map((s) => `${s.optionId}x${s.quantity}`).join(',')}</output>
    </>
  )
}

describe('QuantityOptionSelector', () => {
  let view: Rendered | undefined
  afterEach(() => { view?.unmount(); vi.restoreAllMocks() })

  it('notifies the parent without a "Cannot update a component while rendering" warning', () => {
    const consoleError = vi.spyOn(console, 'error')
    const onSelected = vi.fn()
    view = render(<Parent onSelected={onSelected} />)
    const select = view.container.querySelector<HTMLSelectElement>('#option-select')

    changeValue(select, '1')
    changeValue(select, '2')
    click(byText(view.container, 'button', '+'))
    click(view.container.querySelector('button[aria-label="50ml 옵션 제거"]'))

    expect(consoleError).not.toHaveBeenCalled()
    expect(onSelected.mock.calls.map(([s]) => s.map((o: SelectedOption) => `${o.optionId}x${o.quantity}`).join(','))).toEqual([
      '1x1',
      '1x1,2x1',
      '1x2,2x1',
      '2x1',
    ])
    expect(view.container.querySelector('output')?.textContent).toBe('2x1')
  })

  it('ignores re-selecting an option that is already chosen', () => {
    const onSelected = vi.fn()
    view = render(<Parent onSelected={onSelected} />)
    const select = view.container.querySelector<HTMLSelectElement>('#option-select')
    changeValue(select, '2')
    changeValue(select, '2')
    expect(onSelected).toHaveBeenCalledTimes(1)
  })
})
