import { Link } from 'react-router-dom'
import { useAuth } from '../auth'
import MonthView from '../components/MonthView'

export default function HomePage() {
  const { me, can } = useAuth()
  if (!me) return null
  return (
    <section>
      <div className="row space">
        <h2>Welcome, {me.firstName}</h2>
        <Link className="button" to="/check-in">Check in or end my day</Link>
      </div>
      {can('attendance.read.own') ? <MonthView source="/api/attendance/month" /> : <p className="muted">Roles: {me.roles.join(', ')}</p>}
    </section>
  )
}
