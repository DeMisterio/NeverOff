# NeverOff — Self-Hosted Supabase Migration Runbook

**Audience:** an agent (human or AI) with SSH access to a fresh Oracle Cloud VM, a
Cloudflare account, and the owner's existing Supabase project credentials. This
document is the complete spec — follow it top to bottom.

**Goal:** stop depending on `*.supabase.co`. Run the full Supabase stack
(Postgres, Auth, PostgREST, Realtime, Storage, Kong gateway) on a VPS the owner
controls, with file storage backed by Cloudflare R2 instead of local disk, and
point the Android app at that VPS exclusively.

**Why this actually solves the RKN-block problem:** the app currently talks to
`supabase.co`, a domain/IP range Roskomnadzor can and does target. Once the
backend lives on your own domain + your own server, there is nothing
Supabase-branded left to block — RKN would have to block *your* domain
specifically, the same way it would for any other site. Section 9 covers how to
make that domain reasonably resilient. This is standard "own your
infrastructure" practice, not a trick — treat it that way (proper backups,
TLS, monitoring), because you're now the ops team for a production chat
backend.

**Secrets you must get from the owner before starting — never invent these:**
- Old project's Postgres connection string (Settings → Database → Connection
  string, use the `postgres` user / direct connection, not the pooler) and its
  `service_role` key.
- A domain name you can point DNS for (subdomain like `api.neveroff.app` is
  fine).
- A Cloudflare account with R2 enabled (R2 needs a payment method on file even
  though the free tier is generous — that's Cloudflare's requirement, not
  optional).
- An Oracle Cloud account (email + payment card for identity verification —
  Always Free resources are genuinely free, but Oracle requires a card on
  signup).

Do not commit any secret from this list to git. Everything sensitive belongs
in the VPS's `.env` (server-side) and the repo's `.env` (client-side, already
gitignored — confirmed in `.gitignore`).

---

## 0. Architecture

```
                                   ┌─────────────────────────────┐
                                   │   Cloudflare (DNS, proxied)  │
                                   │   api.<yourdomain>           │
                                   └───────────────┬──────────────┘
                                                    │ HTTPS 443
                                   ┌────────────────▼──────────────┐
                                   │   Oracle Cloud — Ampere A1     │
                                   │   Ubuntu 22.04 VM               │
                                   │                                  │
                                   │  Caddy (reverse proxy, auto-TLS)│
                                   │        │                        │
                                   │        ▼                        │
                                   │  Kong (Supabase API gateway)    │
                                   │   /auth  /rest  /realtime /storage│
                                   │        │        │        │       │
                                   │   GoTrue  PostgREST  Realtime    │
                                   │        │                        │
                                   │   Postgres (Docker volume)      │
                                   │                                  │
                                   │  storage-api ──────────────────┐│
                                   └──────────────────────────────────┼┘
                                                                       │
                                                        S3 API calls   │
                                                                       ▼
                                                        Cloudflare R2 bucket
                                                        (avatars, chat_media)
```

The VM is *both* the relay (TLS-terminating reverse proxy) and the server
(runs every Supabase service) — there's no separate "relay box." Storage
objects never touch the VM's disk; `storage-api` streams them straight to R2.

---

## 1. Oracle Cloud instance

### Shape and sizing

Use the **Always Free Ampere A1 Compute** allowance in full on a single
instance rather than splitting it:

| Parameter | Value | Why |
|---|---|---|
| Shape | `VM.Standard.A1.Flex` | The only free ARM shape; Supabase's images all publish `arm64` variants so this works natively. |
| OCPUs | 4 (the entire free allowance) | Supabase self-host runs ~10 containers (db, kong, auth, rest, realtime, storage, meta, studio, imgproxy, vector/logflare, pooler). 4 OCPUs gives real headroom; splitting across multiple small VMs adds networking complexity for no benefit here. |
| Memory | 24 GB (the entire free allowance) | Postgres + Realtime are the memory-hungry pieces; give Postgres a proper `shared_buffers` instead of starving it. |
| Boot volume | 100 GB (from the 200 GB free block storage allowance) | Leaves 100 GB free for a second volume (WAL archives / local backup staging) later if needed. |
| Image | **Canonical Ubuntu 22.04 (aarch64)** | Best Docker/arm64 package support and documentation coverage; avoid Oracle Linux unless you're already fluent in its firewall (`firewalld`) and SELinux defaults. |
| Region | Pick the free-tier region geographically closest to your users (e.g. `eu-frankfurt-1` for Europe-adjacent traffic) — Ampere A1 capacity is often exhausted in popular regions, so be ready to retry provisioning over a few hours/days if you get a capacity error. |
| Networking | Assign a **reserved (static) public IP**, not ephemeral — DNS should point at an IP that survives a reboot. |

### Provisioning steps

1. Console → Compute → Instances → Create Instance.
2. Name it (e.g. `neveroff-backend`), pick the image and shape above.
3. Under "Add SSH keys," upload the admin's public key — do not use
   password auth.
4. Networking: put it in a new or existing VCN, **create/attach a reserved
   public IP** (Networking → IP Management → Reserved Public IPs).
5. After creation, edit the VCN's **Security List** (or attach a Network
   Security Group) to allow ingress:
   - TCP 22 from your admin IP only (not `0.0.0.0/0`)
   - TCP 80 from `0.0.0.0/0` (ACME HTTP-01 challenge)
   - TCP 443 from `0.0.0.0/0`
   Oracle's default security list only opens 22 — 80/443 must be added
   explicitly or Caddy will never be reachable.
6. On the instance itself, `ufw` should mirror this (defense in depth):
   ```bash
   sudo ufw default deny incoming
   sudo ufw allow OpenSSH
   sudo ufw allow 80/tcp
   sudo ufw allow 443/tcp
   sudo ufw enable
   ```

### Avoiding Always-Free reclamation

Oracle reclaims Always Free compute instances that are idle for 7 days
(CPU, network, and memory all under ~20% utilization the whole window). A
lightly-used chat backend can trip this. Mitigate with a lightweight
keep-alive rather than fake load:
```bash
# crontab -e, runs every 4 hours, just enough real activity to register
0 */4 * * * curl -fsS -o /dev/null https://api.<yourdomain>/rest/v1/ -H "apikey: $ANON_KEY"
```
This hits your own real health endpoint — not synthetic CPU burn — so it
also doubles as an uptime check.

---

## 2. Base OS setup

```bash
sudo apt update && sudo apt -y upgrade
sudo apt -y install docker.io docker-compose-plugin git curl fail2ban unattended-upgrades
sudo systemctl enable --now docker fail2ban
sudo usermod -aG docker $USER   # log out/in after this
```

Enable unattended security upgrades (`sudo dpkg-reconfigure -plow
unattended-upgrades`) and confirm `fail2ban` is guarding SSH
(`sudo fail2ban-client status sshd`).

---

## 3. DNS (Cloudflare)

1. Add the domain to Cloudflare if it isn't already (Cloudflare becomes the
   authoritative nameserver).
2. Create an `A` record: `api` → the VM's reserved public IP, **proxy status:
   Proxied (orange cloud)**.
   - Proxied mode means clients resolve to Cloudflare's anycast IPs, not your
     origin IP directly — your VM's real address isn't published in DNS. This
     is a genuine resilience win (a block would have to target the whole
     domain rather than one small VPS's /32), but be aware RKN has at times
     blocked broad Cloudflare IP ranges too — there's no permanent guarantee
     either way. Keep the origin IP itself private (don't post it anywhere
     public) as a second layer.
3. SSL/TLS mode: **Full (strict)** — Cloudflare↔origin is encrypted with a
   real cert (Caddy gets one from Let's Encrypt in step 5), not just
   Cloudflare↔client.
4. Under Cloudflare → SSL/TLS → Origin Server, you can optionally issue a
   Cloudflare Origin Certificate instead of Let's Encrypt for the
   origin-to-edge leg; Let's Encrypt via Caddy is simpler and sufficient here,
   so the runbook below uses that.

---

## 4. Deploy self-hosted Supabase

```bash
git clone --depth 1 https://github.com/supabase/supabase.git /opt/supabase-src
cd /opt/supabase-src/docker
cp .env.example .env
```

Generate secrets — **do not reuse the example file's placeholder values**:

```bash
# Postgres password
openssl rand -base64 32

# JWT secret (must be >= 32 chars)
openssl rand -base64 40
```

Edit `/opt/supabase-src/docker/.env`:
- `POSTGRES_PASSWORD` — the generated password above.
- `JWT_SECRET` — the generated secret above.
- `ANON_KEY` / `SERVICE_ROLE_KEY` — generate these as JWTs signed with your
  `JWT_SECRET` using the Supabase CLI (`supabase gen keys` in newer CLI
  versions) or the generator script Supabase documents at
  https://supabase.com/docs/guides/self-hosting/docker#generate-api-keys —
  follow whatever the current official docs show; the exact command has
  changed across CLI versions so don't hardcode one here.
- `SITE_URL`, `API_EXTERNAL_URL`, `SUPABASE_PUBLIC_URL` → `https://api.<yourdomain>`.
- `DISABLE_SIGNUP` → `false` only if the app allows self-serve signup;
  otherwise match the existing hosted project's setting.
- Leave `STUDIO_PORT` etc. as defaults; Studio will only be reachable via SSH
  tunnel (see Section 8), not exposed publicly.

Bring the stack up:
```bash
docker compose pull
docker compose up -d
docker compose ps   # confirm every service is "healthy"/"running"
```

---

## 5. Reverse proxy + TLS (Caddy)

```bash
sudo apt -y install debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt -y install caddy
```

`/etc/caddy/Caddyfile`:
```
api.<yourdomain> {
    reverse_proxy localhost:8000   # Kong's default exposed port in docker-compose.yml
}
```
```bash
sudo systemctl reload caddy
```
Caddy handles the ACME HTTP-01 challenge and cert renewal automatically —
nothing else to configure as long as ports 80/443 are open (Section 1).

Verify: `curl -I https://api.<yourdomain>/rest/v1/` should return a JSON
response (401 without an API key is expected and fine — it proves the whole
chain is wired).

---

## 6. Wire in Cloudflare R2 for Storage

1. Cloudflare dashboard → R2 → Create bucket. Create **two buckets** to match
   the existing bucket names exactly: `avatars` and `chat_media`.
2. R2 → Manage API Tokens → create a token scoped to **only these two
   buckets**, permission "Object Read & Write." Note the Access Key ID,
   Secret Access Key, and the account's S3 endpoint
   (`https://<account_id>.r2.cloudflarestorage.com`).
3. In `/opt/supabase-src/docker/.env`, add the S3 backend variables the
   `storage-api` image reads (confirm current variable names against
   https://github.com/supabase/storage-api's README for the version you
   pulled — they're stable but double-check):
   ```
   STORAGE_BACKEND=s3
   GLOBAL_S3_BUCKET=<a single bucket, OR configure per-bucket if the image supports it>
   REGION=auto
   GLOBAL_S3_ENDPOINT=https://<account_id>.r2.cloudflarestorage.com
   GLOBAL_S3_FORCE_PATH_STYLE=true
   AWS_ACCESS_KEY_ID=<R2 access key>
   AWS_SECRET_ACCESS_KEY=<R2 secret key>
   ```
   Note: Supabase's storage-api historically expects **one** S3 bucket as the
   backing store, with your logical buckets (`avatars`, `chat_media`)
   represented as *prefixes inside it*, not as two separate physical S3
   buckets. Check the storage-api version you pulled: if it's the
   single-bucket model, create one R2 bucket (e.g. `neveroff-storage`) and let
   Supabase's own `storage.buckets` table keep the `avatars`/`chat_media`
   logical split — the SQL in Section 7 already creates those logical buckets
   correctly either way. Don't create redundant R2 buckets if the version you
   deployed only supports one.
4. `docker compose up -d storage` to restart just that service with the new
   env vars, then check `docker compose logs storage` for S3 connection
   errors before moving on.

---

## 7. Apply the existing schema

The repo already has the full schema as ordered migrations at
`supabase/migrations/01_schema.sql` through `06_missing_backend_setup.sql`.
Apply them in order against the **new** self-hosted Postgres:

```bash
for f in supabase/migrations/*.sql; do
  psql "postgresql://postgres:<POSTGRES_PASSWORD>@localhost:5432/postgres" -f "$f"
done
```
(Run this from the VM, or through an SSH tunnel from your workstation —
never expose Postgres's 5432 publicly; it isn't in the Section 1 ingress
list on purpose.)

This recreates: `profiles`, `chats`, `chat_participants`, `messages`,
`versions` tables, all RLS policies, the `delete_chat_for_all` RPC, the
`avatars`/`chat_media` storage buckets and their policies, and adds `messages`
+ `chats` to the `supabase_realtime` publication.

---

## 8. Migrate data from the hosted project

Do this during a short announced maintenance window — users will see errors
if they write to the old project after the dump but before the app is
repointed.

**Postgres data** (schema is already applied via Section 7 — this copies
rows only):
```bash
# From your workstation or the VM, using the OLD project's connection string
pg_dump "postgresql://postgres:<OLD_DB_PASSWORD>@<old-host>:5432/postgres" \
  --data-only --schema=public --schema=auth \
  --exclude-table-data='auth.schema_migrations' \
  -f neveroff_data.sql

# Load into the new instance
psql "postgresql://postgres:<NEW_DB_PASSWORD>@<new-host>:5432/postgres" -f neveroff_data.sql
```
Include the `auth` schema's data so existing user accounts (and their
password hashes / identities) carry over — users shouldn't have to
re-register. Test login against the new instance with one real account
before the full cutover.

**Storage objects** (old Supabase Storage → R2), using `rclone` so files are
streamed rather than round-tripped through a laptop:
```bash
sudo apt -y install rclone
rclone config   # create two remotes: "old_supabase" (S3-compatible, endpoint
                # = https://<old-project-ref>.supabase.co/storage/v1/s3,
                # region=auto) and "r2" (endpoint = the R2 endpoint from
                # Section 6, using the same R2 API token)

rclone sync old_supabase:avatars r2:<bucket>/avatars --progress
rclone sync old_supabase:chat_media r2:<bucket>/chat_media --progress
```
(Adjust the destination path to match whatever bucket/prefix layout you
settled on in Section 6.) The old project's S3-compatible storage endpoint
and its own S3 access keys are found in the old project's Settings →
Storage → S3 Connection — this is separate from the project's `anon`/
`service_role` keys.

After both syncs finish, spot-check: pick a `chat_media` object referenced in
the migrated `messages` table and confirm it downloads correctly from the new
instance's storage API.

---

## 9. Point the app at the new instance only

Edit the repo's root `.env` (already gitignored — never commit it):
```
SUPABASE_URL=https://api.<yourdomain>
SUPABASE_PUBLISHABLE_KEY=<the new instance's ANON_KEY from Section 4>
```
Rebuild the app (`./gradlew assembleDebug` or your usual build) — the value
is baked into `BuildConfig` at compile time
([app/build.gradle.kts:32-33](app/build.gradle.kts:32)), so existing installed
builds keep hitting the old URL until users update. Bump the `versions` table
entry (Section 7's migration created it) so the in-app update check
(`UpdateManager`) prompts existing users to upgrade to the build pointing at
the new backend.

There is nothing else in the codebase hardcoding `supabase.co` —
[SupabaseClient.kt](app/src/main/java/com/neveroff/data/remote/SupabaseClient.kt)
reads only `BuildConfig.SUPABASE_URL`/`SUPABASE_PUBLISHABLE_KEY`, so this one
`.env` edit is the entire client-side cutover.

---

## 10. Validation checklist before declaring done

- [ ] `docker compose ps` shows every Supabase service healthy, 7+ days
      uptime under real traffic without OOM restarts.
- [ ] Fresh signup, login, and password reset work end-to-end against the new
      instance.
- [ ] An existing (migrated) account can log in with its original password.
- [ ] Sending a chat message round-trips over Realtime between two devices.
- [ ] Uploading a new avatar and a new chat media file both land in R2 (check
      the R2 bucket in the Cloudflare dashboard) and are retrievable in-app.
- [ ] A previously-uploaded (migrated) avatar/media file still loads
      correctly from R2.
- [ ] `delete_chat_for_all` RPC still deletes chats/messages/participants
      correctly (cascades work the same on the new Postgres).
- [ ] TLS: `curl -vI https://api.<yourdomain>` shows a valid cert chain, no
      warnings.
- [ ] Old Supabase project is left running read-only (don't delete it) for at
      least a couple of weeks after cutover, in case of a rollback need.

---

## 11. Ongoing operations

**Backups** — nightly `pg_dump` to R2 (a third bucket, or a prefix in the
existing one), retained on a rotation:
```bash
0 3 * * * pg_dump "postgresql://postgres:<PW>@localhost:5432/postgres" \
  | gzip | rclone rcat r2:neveroff-backups/pg-$(date +\%F).sql.gz
```
**Updates** — `docker compose pull && docker compose up -d` periodically,
after reading the `supabase/supabase` repo's release notes (this is a moving
target upstream; don't blind-upgrade production without a staging pass).

**Monitoring** — at minimum, an uptime check on
`https://api.<yourdomain>/rest/v1/` (the keep-alive cron from Section 1
doubles as this) and disk-space alerting on the boot volume (Postgres WAL
growth is the usual culprit).

**Studio access** — don't expose port 3000 (Studio) publicly. Reach it via
SSH tunnel when needed: `ssh -L 3000:localhost:3000 <vm>` then browse
`localhost:3000` locally.
