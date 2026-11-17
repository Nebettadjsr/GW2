# ADR-012 — Host Nginx terminates public TLS

## Status and authority

Accepted for the owner-only HTTPS preparation on 2026-11-15. Native architecture review:
[#213, initial decision](https://github.com/Nebettadjsr/GW2/issues/213#issuecomment-6891448348)
and [reviewed Origin correction](https://github.com/Nebettadjsr/GW2/issues/213#issuecomment-6891514252).
The supervisor records that reviewed decision here. Public application release remains a separate
Product Owner action; runtime evidence belongs in `CURRENT_STATE_SPEC.md`.

## Context and selection

ADR-011 already assigns static assets and internal `/api` forwarding to the frontend Nginx
container. Its only host binding is loopback. The selected public domain is `gw2-crafting.de`,
with `www` aliasing the apex. Public TLS remained undecided.

Select Ubuntu-packaged host Nginx and Certbot, keeping the existing application containers.
The host edge terminates TLS and proxies to the loopback frontend. Certbot uses HTTP-01 webroot
validation, root-owned host credentials, no-email registration, the distro renewal timer, and a
successful-renewal hook that runs `nginx -t` before reloading Nginx. The certificate covers both
names; `www` redirects to the apex. Port 80 serves ACME challenges and otherwise redirects to HTTPS.

Caddy is viable, but adds a second proxy technology and certificate-state model without removing
the accepted frontend Nginx. Host Nginx reuses the known forwarding semantics; distro Certbot keeps
certificate issuance independent of application restarts. DNS-01 would require DNS credentials or
manual renewal without a demonstrated need. No Oracle-specific service or paid feature is needed.

## Origin, forwarding and prelaunch boundary

`AccountAccessHttp.isSameOrigin` also accepts the backend-observed internal HTTP origin. Merely
adding `https://gw2-crafting.de` does not reject a presented `http://gw2-crafting.de` Origin.
Therefore the host edge checks **all proxied application paths**, not an API-path subset:

- GET, HEAD and OPTIONS continue to the ordinary backend authentication and authorization.
- Every other method returns 403 unless Origin is exactly `https://gw2-crafting.de`.
- Missing, foreign and plaintext same-host origins are denied for unsafe methods.

HTTP-level method/Origin maps and a return-only conditional in the proxy location implement that
check. Backend authentication and its existing Origin check remain intact. Deployment also supplies
the exact `GW2_ACCOUNT_ACCESS_ALLOWED_ORIGINS=https://gw2-crafting.de`; no wildcard, reflected origin
or forwarded-header trust is introduced. The edge preserves Host, Origin, cookies and account
headers, returns Set-Cookie unchanged, and clears client Forwarded/X-Forwarded headers. Cookie
Secure/HttpOnly/SameSite/host-only behavior remains the existing application's responsibility.

Before public release, application access is restricted to the owner's current source address and
local verification through an edge allowlist. Certificate issuance and redirects do not authorize
public application access. HSTS preloading is not enabled. Edge logs exclude queries, client IPs,
headers and bodies, and use bounded host rotation. TLS/ACME private material stays on the host,
outside Git and the application containers.

## Verification required

Verify trusted TLS for both names, redirects, automatic renewal and validated reload, the owner
access gate, unchanged private container ports, and ordinary application health. Separately check
unsafe requests with missing, foreign, plaintext same-host and canonical HTTPS Origins; only the
last reaches backend validation. Safe methods still reach ordinary application authorization.
Real key proof/cookie behavior is separate evidence. No capacity test or new monitoring service
is part of this decision.

Primary references: [Nginx TLS](https://nginx.org/en/docs/http/configuring_https_servers.html),
[Certbot webroot and renewal](https://eff-certbot.readthedocs.io/en/stable/using.html), and
[HTTP-01](https://letsencrypt.org/docs/challenge-types/).
