import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type User } from '../api'
import MonthView from '../components/MonthView'

export default function UserAttendancePage() {
  const { id } = useParams()
  const [user, setUser] = useState<User | null>(null)
  useEffect(() => { api<User>(`/api/users/${id}`).then(setUser).catch(() => undefined) }, [id])
  return (
    <section>
      <p><Link to={`/users/${id}`}>← Back to employee</Link></p>
      <h2>Attendance{user ? `: ${user.firstName} ${user.lastName}` : ''}</h2>
      <MonthView source={`/api/attendance/users/${id}/month`} />
    </section>
  )
}
