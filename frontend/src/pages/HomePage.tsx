import { useAuth } from '../auth'

export default function HomePage() {
  const { me } = useAuth()
  if (!me) return null
  return (
    <section>
      <h2>Welcome, {me.firstName}</h2>
      <p className="muted">Roles: {me.roles.join(', ')}</p>
      <p>
        Attendance, leave and payroll arrive in the next phases. For now this app manages users, roles and
        permissions, sites with their allowed networks and GPS areas, and the audit log.
      </p>
    </section>
  )
}
