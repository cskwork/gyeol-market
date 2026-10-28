import { Link } from 'react-router-dom'
import styles from './NotFound.module.css'

export default function NotFound() {
  return (
    <div className={styles.page}>
      <div className={styles.inner}>
        <p className={styles.code}>404</p>
        <h1 className={styles.title}>페이지를 찾을 수 없습니다</h1>
        <p className={styles.desc}>
          요청하신 페이지가 존재하지 않거나 이동되었습니다.
        </p>
        <Link to="/" className={styles.homeLink}>
          홈으로 돌아가기
        </Link>
      </div>
    </div>
  )
}
