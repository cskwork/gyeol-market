import { useState, useCallback, useEffect } from 'react'
import type { ProductOptionSummary } from '@/lib/types'
import styles from './QuantityOptionSelector.module.css'

export interface SelectedOption {
  optionId: number
  optionName: string
  optionPrice: number
  quantity: number
}

interface QuantityOptionSelectorProps {
  options: ProductOptionSummary[]
  /** Product sale price; each option's price is added on top of it. */
  unitPrice: number
  onChange: (selected: SelectedOption[]) => void
}

function formatKrw(amount: number): string {
  return amount.toLocaleString('ko-KR') + '원'
}

export default function QuantityOptionSelector({ options, unitPrice, onChange }: QuantityOptionSelectorProps) {
  const [selected, setSelected] = useState<SelectedOption[]>([])

  // Only options the backend reports as ON_SALE can be bought.
  const activeOptions = options.filter((o) => o.status === 'ON_SALE')

  // A product with a single purchasable option (e.g. "기본") needs no choice:
  // preselect it so the buy buttons work straight away.
  const soleOption = activeOptions.length === 1 ? activeOptions[0] : null
  useEffect(() => {
    if (!soleOption) return
    const next = [{ optionId: soleOption.optionId, optionName: soleOption.optionName, optionPrice: soleOption.optionPrice, quantity: 1 }]
    setSelected(next)
    onChange(next)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- run once per product option
  }, [soleOption?.optionId])

  const handleSelect = useCallback(
    (e: React.ChangeEvent<HTMLSelectElement>) => {
      const optionId = Number(e.target.value)
      if (!optionId) return

      const opt = options.find((o) => o.optionId === optionId)
      if (!opt) return

      // Reset selector
      e.target.value = ''

      setSelected((prev) => {
        if (prev.some((s) => s.optionId === optionId)) return prev
        const next: SelectedOption[] = [
          ...prev,
          { optionId, optionName: opt.optionName, optionPrice: opt.optionPrice, quantity: 1 },
        ]
        onChange(next)
        return next
      })
    },
    [options, onChange],
  )

  const updateQty = useCallback(
    (optionId: number, delta: number) => {
      setSelected((prev) => {
        const next = prev.map((s) => {
          if (s.optionId !== optionId) return s
          const opt = options.find((o) => o.optionId === optionId)
          const max = opt?.availableQuantity ?? 99
          const newQty = Math.max(1, Math.min(max, s.quantity + delta))
          return { ...s, quantity: newQty }
        })
        onChange(next)
        return next
      })
    },
    [options, onChange],
  )

  const removeOption = useCallback(
    (optionId: number) => {
      setSelected((prev) => {
        const next = prev.filter((s) => s.optionId !== optionId)
        onChange(next)
        return next
      })
    },
    [onChange],
  )

  return (
    <div className={styles.root}>
      {/* Dropdown */}
      {activeOptions.length > 1 && (
        <div className={styles.dropdownWrap}>
          <label className={styles.dropdownLabel} htmlFor="option-select">
            옵션 선택
          </label>
          <select
            id="option-select"
            className={styles.dropdown}
            onChange={handleSelect}
            defaultValue=""
            aria-label="상품 옵션 선택"
          >
            <option value="" disabled>
              옵션을 선택하세요
            </option>
            {activeOptions.map((opt) => (
              <option key={opt.optionId} value={opt.optionId}>
                {opt.optionName}
                {opt.optionPrice > 0 ? ` (+${formatKrw(opt.optionPrice)})` : ''}
                {opt.availableQuantity !== null && opt.availableQuantity <= 5
                  ? ` (재고 ${opt.availableQuantity}개)`
                  : ''}
              </option>
            ))}
          </select>
          <span className={styles.dropdownChevron} aria-hidden="true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </span>
        </div>
      )}

      {/* Selected option rows */}
      {selected.length > 0 && (
        <div className={styles.selectedList} aria-label="선택된 옵션">
          {selected.map((s) => {
            const opt = options.find((o) => o.optionId === s.optionId)
            const maxQty = opt?.availableQuantity ?? 99
            const lineTotal = (unitPrice + s.optionPrice) * s.quantity

            return (
              <div key={s.optionId} className={styles.selectedRow}>
                <div className={styles.selectedHeader}>
                  <span className={styles.selectedName}>{s.optionName}</span>
                  {!soleOption && <button
                    type="button"
                    className={styles.removeBtn}
                    onClick={() => removeOption(s.optionId)}
                    aria-label={`${s.optionName} 옵션 제거`}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                      <line x1="18" y1="6" x2="6" y2="18" />
                      <line x1="6" y1="6" x2="18" y2="18" />
                    </svg>
                  </button>}
                </div>

                <div className={styles.stepperRow}>
                  <div className={styles.stepper} role="group" aria-label={`${s.optionName} 수량`}>
                    <button
                      type="button"
                      className={styles.stepBtn}
                      onClick={() => updateQty(s.optionId, -1)}
                      disabled={s.quantity <= 1}
                      aria-label="수량 줄이기"
                    >
                      −
                    </button>
                    <span className={styles.stepCount} aria-live="polite" aria-label={`수량 ${s.quantity}`}>
                      {s.quantity}
                    </span>
                    <button
                      type="button"
                      className={styles.stepBtn}
                      onClick={() => updateQty(s.optionId, 1)}
                      disabled={s.quantity >= maxQty}
                      aria-label="수량 늘리기"
                    >
                      +
                    </button>
                  </div>
                  <span className={styles.subtotal} aria-label={`소계 ${formatKrw(lineTotal)}`}>
                    {formatKrw(lineTotal)}
                  </span>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
