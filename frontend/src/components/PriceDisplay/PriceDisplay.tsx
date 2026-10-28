import styles from './PriceDisplay.module.css'

interface PriceDisplayProps {
  salePrice: number
  originalPrice: number
  discountRate: number
}

function formatKrw(amount: number): string {
  return amount.toLocaleString('ko-KR') + '원'
}

export default function PriceDisplay({ salePrice, originalPrice, discountRate }: PriceDisplayProps) {
  const hasDiscount = discountRate > 0 && originalPrice > salePrice
  // The API sends one decimal (e.g. 32.3); shelf labels show whole percents, rounded down.
  const rate = Math.floor(discountRate)

  return (
    <span className={styles.root}>
      {hasDiscount && (
        <span className={styles.discount} aria-label={`${rate}% 할인`}>
          {rate}%
        </span>
      )}
      <strong className={styles.salePrice}>{formatKrw(salePrice)}</strong>
      {hasDiscount && (
        <span className={styles.original} aria-label={`정가 ${formatKrw(originalPrice)}`}>
          {formatKrw(originalPrice)}
        </span>
      )}
    </span>
  )
}
