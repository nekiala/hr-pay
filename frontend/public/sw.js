/* HR Pay service worker: keeps the app's own files so it opens without a connection, and shows push notifications.
   It never keeps answers from /api: those hold personal data and must always come from the server. */
const CACHE = 'hrpay-shell-v1'
const SHELL = ['/', '/index.html', '/manifest.webmanifest', '/favicon.svg', '/icons/icon-192.png']

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()))
})

const PRIVATE = /^\/(api|iclock|actuator|oauth2|login\/oauth2|logout)(\/|$)/

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return
  const url = new URL(request.url)
  if (url.origin !== self.location.origin || PRIVATE.test(url.pathname)) return
  if (request.mode === 'navigate') {
    // Pages: the network first, so a new version shows at once; the kept copy without a connection.
    event.respondWith(fetch(request).then((response) => {
      if (response.ok) {
        const copy = response.clone()
        caches.open(CACHE).then((c) => c.put('/index.html', copy))
      }
      return response
    }).catch(() => caches.match('/index.html')))
    return
  }
  if (url.pathname.startsWith('/assets/') || url.pathname.startsWith('/icons/') || url.pathname.startsWith('/fonts/')) {
    // Built files carry their version in their name: kept once, served from the cache.
    event.respondWith(caches.match(request).then((hit) => hit || fetch(request).then((response) => {
      if (response.ok) {
        const copy = response.clone()
        caches.open(CACHE).then((c) => c.put(request, copy))
      }
      return response
    })))
  }
})

// A push carries nothing: the notifications are read from HR Pay with the person's session.
self.addEventListener('push', (event) => {
  event.waitUntil((async () => {
    let items = []
    try {
      const r = await fetch('/api/notifications?limit=5', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      if (r.ok) items = ((await r.json()).items || []).filter((n) => !n.readAt)
    } catch (e) { /* offline or signed out */ }
    if (items.length === 0) {
      await self.registration.showNotification('HR Pay', { body: '…', tag: 'hrpay', icon: '/icons/icon-192.png', data: { link: '/' } })
      return
    }
    const n = items[0]
    const more = items.length > 1 ? ` (+${items.length - 1})` : ''
    await self.registration.showNotification(n.title + more, {
      body: n.body || '', tag: 'hrpay', renotify: true, icon: '/icons/icon-192.png', badge: '/icons/icon-192.png',
      data: { link: n.link || '/', id: n.id },
    })
  })())
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const link = (event.notification.data && event.notification.data.link) || '/'
  const target = new URL(link.startsWith('/') && !link.startsWith('//') ? link : '/', self.location.origin).href
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((all) => {
    for (const c of all) {
      if (c.url.startsWith(self.location.origin) && 'focus' in c) {
        c.navigate(target)
        return c.focus()
      }
    }
    return self.clients.openWindow(target)
  }))
})
