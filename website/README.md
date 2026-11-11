# Product website

Static pages, French and English (`?lang=en`, or the visitor's browser language), no build step.

Published at https://hrpay.cloud, with the app at https://platform.hrpay.cloud. Each page sets two addresses on `<body>`:

- `data-app`: the HR Pay server customers sign in to (https://platform.hrpay.cloud). The pages link to its `/trial` form,
  `/status` page and `/legal/...` documents.
- `data-demo`: the demo server (see "Demo server" in `deploy/README.md`). Empty: the demo buttons are hidden.

The Caddy in `deploy/` serves this folder on `SITE_DOMAIN` (see `deploy/README.md`), so `upgrade.sh` publishes it with
the app. Any other static host works too. Prices are not on the page: trial requests come to the provider console (*Trial requests*), and the offer is sent from there.

## Salary calculators and articles

- `calculateur-salaire-rdc.html`, `calculateur-salaire-congo.html` and `cout-employeur.html` call the app's public
  calculator (`GET /api/public/calculator`, no sign-in, any site may call it). It works out one month with the payroll
  engine and the rates shipped in the country packs (`backend/src/main/resources/packs`), never a company's own rates.
  Set `APP_PUBLIC_CALCULATOR_COUNTRIES` (default `CD,CG`) to open or close a country, for instance until its rates are
  signed off. If the API is on another address than the app, add `data-api="https://api..."` on `<body>`.
- Visitors who ask for the result by email get it from the platform's mail server; they show up in the provider console
  under *Trial requests > Salary calculator leads*.
- `articles/` holds short French articles on the rules (SMIG, IPR, CNSS, ITS). Update their date and text when a law
  changes.
- The website's address (https://hrpay.cloud) is in `sitemap.xml`, `robots.txt` and each page's canonical link.
- Opened from `localhost` (for instance `python3 -m http.server 8090` in this folder), the pages use the local app
  (API on 8082, app on 5173).
