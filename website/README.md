# Product website

One static page, French and English (`?lang=en`, or the visitor's browser language), no build step.

Before publishing, set the two addresses on `<body>` in `index.html`:

- `data-app`: the HR Pay server customers sign in to. The page links to its `/trial` form, `/status` page and `/legal/...` documents.
- `data-demo`: the demo server (see "Demo server" in `deploy/README.md`).

Put the folder on any static host (the Caddy in `deploy/` can serve it with `file_server`). Prices are not on the page: trial requests come to the provider console (*Trial requests*), and the offer is sent from there.
