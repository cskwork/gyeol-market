import styles from './ShoppingSteps.module.css'

export default function ShoppingSteps({ current }: { current: 1 | 2 | 3 }) {
  return (
    <ol className={styles.steps} aria-label="주문 진행 단계">
      {['장바구니', '주문 확인', '주문 완료'].map((name, index) => (
        <li key={name} aria-current={current === index + 1 ? 'step' : undefined}>
          <span className={styles.number} aria-hidden="true">{index + 1}</span>
          {name}
        </li>
      ))}
    </ol>
  )
}
