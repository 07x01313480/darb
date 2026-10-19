-- Darb backend for Supabase: live traffic from drivers + shared road reports.
-- Run once in Supabase → SQL Editor → New query → paste → Run. Safe to re-run.
--
-- Privacy model
--   * Traffic samples store NO user id: only a ~150 m grid cell, a heading bucket, a speed
--     and the server minute. They are deleted after 8 days.
--   * Reports store the author's id (so authors can delete their own and votes can be deduplicated),
--     but the id is never returned to clients.
--   * Clients never touch the tables directly; everything goes through the functions below.

-- ───────────────────────── Tables ─────────────────────────

create table if not exists public.traffic_samples (
  id    bigint generated always as identity primary key,
  cx    integer  not null,                 -- floor(lat / 0.0015)  (~167 m north–south)
  cy    integer  not null,                 -- floor(lng / 0.0015)  (~140 m east–west in Iraq)
  hb    smallint not null check (hb between 0 and 7),   -- heading bucket, 45° each, 0 = north
  speed real     not null check (speed between 0 and 200),  -- km/h
  t     timestamptz not null default date_trunc('minute', now())
);
create index if not exists traffic_samples_cell_t on public.traffic_samples (cx, cy, t);
create index if not exists traffic_samples_t      on public.traffic_samples (t);

-- Per-user submission counter for rate limiting (holds no location).
create table if not exists public.traffic_quota (
  user_id uuid        not null references auth.users (id) on delete cascade,
  w       timestamptz not null,            -- minute window
  n       integer     not null default 0,
  primary key (user_id, w)
);

create table if not exists public.reports (
  id         uuid primary key default gen_random_uuid(),
  type       text not null check (type in ('jam','police','accident','hazard','closure','camera')),
  lat        double precision not null check (lat between -90 and 90),
  lng        double precision not null check (lng between -180 and 180),
  user_id    uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  gone       smallint not null default 0     -- "not there anymore" votes from other users
);
create index if not exists reports_live on public.reports (expires_at);
create index if not exists reports_pos  on public.reports (lat, lng);
create index if not exists reports_user on public.reports (user_id, created_at);

create table if not exists public.report_votes (
  report_id uuid not null references public.reports (id) on delete cascade,
  user_id   uuid not null references auth.users (id) on delete cascade,
  primary key (report_id, user_id)
);

-- Lock the tables: RLS on with no policies = no direct access through the API.
alter table public.traffic_samples enable row level security;
alter table public.traffic_quota   enable row level security;
alter table public.reports         enable row level security;
alter table public.report_votes    enable row level security;
revoke all on public.traffic_samples, public.traffic_quota, public.reports, public.report_votes from anon, authenticated;

-- ───────────────────────── Traffic ─────────────────────────

-- Accepts a batch of samples: [{"lat":33.31,"lng":44.36,"speed":42.5,"heading":270}, ...]
-- Returns how many were stored. Signed-in users only.
create or replace function public.submit_traffic(samples jsonb)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  uid uuid := auth.uid();
  s jsonb;
  la double precision; lo double precision; sp double precision; hd double precision;
  stored integer := 0;
  used integer;
begin
  if uid is null then raise exception 'sign in required'; end if;
  if jsonb_typeof(samples) <> 'array' then raise exception 'samples must be an array'; end if;
  if jsonb_array_length(samples) > 60 then raise exception 'too many samples'; end if;

  -- At most 120 samples per user per minute.
  insert into traffic_quota as q (user_id, w, n)
  values (uid, date_trunc('minute', now()), jsonb_array_length(samples))
  on conflict (user_id, w) do update set n = q.n + excluded.n
  returning n into used;
  if used > 120 then raise exception 'rate limit'; end if;

  for s in select * from jsonb_array_elements(samples) loop
    begin
      la := (s->>'lat')::double precision;
      lo := (s->>'lng')::double precision;
      sp := (s->>'speed')::double precision;
      hd := coalesce((s->>'heading')::double precision, 0);
    exception when others then
      continue;  -- skip malformed entries
    end;
    if la is null or lo is null or sp is null
       or la not between -90 and 90 or lo not between -180 and 180
       or sp not between 0 and 200 then
      continue;
    end if;
    insert into traffic_samples (cx, cy, hb, speed)
    values (floor(la / 0.0015)::int, floor(lo / 0.0015)::int,
            (floor((((hd::numeric % 360) + 360) % 360 + 22.5) / 45)::int % 8)::smallint,
            sp::real);
    stored := stored + 1;
  end loop;

  -- Opportunistic cleanup, so no scheduler extension is needed.
  if random() < 0.05 then
    delete from traffic_samples where t < now() - interval '8 days';
    delete from traffic_quota   where w < now() - interval '10 minutes';
    delete from reports         where expires_at < now() - interval '1 day';
  end if;

  return stored;
end;
$$;

-- Live traffic inside a map box: one row per grid cell and direction seen in the last 15 minutes.
--   speed = median km/h now, n = samples, ratio = speed / usual free-flow speed (85th percentile
--   over 7 days) when there is enough history, otherwise null.
create or replace function public.get_traffic(min_lat double precision, min_lng double precision,
                                              max_lat double precision, max_lng double precision)
returns table (lat double precision, lng double precision, hb smallint, speed real, ratio real, n integer)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  with box as (
    select floor(min_lat / 0.0015)::int as x0, floor(max_lat / 0.0015)::int as x1,
           floor(min_lng / 0.0015)::int as y0, floor(max_lng / 0.0015)::int as y1
    where max_lat - min_lat between 0 and 0.6 and max_lng - min_lng between 0 and 0.6
  ),
  cur as (
    select s.cx, s.cy, s.hb,
           percentile_cont(0.5) within group (order by s.speed) as med,
           count(*)::int as cnt
    from traffic_samples s, box
    where s.t > now() - interval '15 minutes'
      and s.cx between box.x0 and box.x1 and s.cy between box.y0 and box.y1
    group by s.cx, s.cy, s.hb
  ),
  ff as (
    select s.cx, s.cy, s.hb,
           percentile_cont(0.85) within group (order by s.speed) as p85,
           count(*)::int as hn
    from traffic_samples s
    join cur on cur.cx = s.cx and cur.cy = s.cy and cur.hb = s.hb
    where s.t > now() - interval '7 days'
    group by s.cx, s.cy, s.hb
  )
  select (cur.cx + 0.5) * 0.0015, (cur.cy + 0.5) * 0.0015, cur.hb, cur.med::real,
         case when ff.hn >= 20 and ff.p85 >= 15 then least(1, cur.med / ff.p85)::real end,
         cur.cnt
  from cur left join ff on ff.cx = cur.cx and ff.cy = cur.cy and ff.hb = cur.hb
  limit 3000;
$$;

-- ───────────────────────── Reports ─────────────────────────

-- Adds a report at a position, or refreshes an identical live one within ~100 m.
create or replace function public.add_report(p_type text, p_lat double precision, p_lng double precision)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  uid uuid := auth.uid();
  life interval;
  rid uuid;
begin
  if uid is null then raise exception 'sign in required'; end if;
  life := case p_type
    when 'jam'      then interval '1 hour'
    when 'police'   then interval '2 hours'
    when 'accident' then interval '2 hours'
    when 'hazard'   then interval '12 hours'
    when 'closure'  then interval '24 hours'
    when 'camera'   then interval '365 days'
  end;
  if life is null then raise exception 'unknown report type'; end if;
  if p_lat not between -90 and 90 or p_lng not between -180 and 180 then raise exception 'bad position'; end if;

  if (select count(*) from reports where user_id = uid and created_at > now() - interval '10 minutes') >= 10 then
    raise exception 'rate limit';
  end if;

  select id into rid from reports
  where type = p_type and expires_at > now() and gone < 2
    and abs(lat - p_lat) < 0.0009 and abs(lng - p_lng) < 0.0011
  order by created_at desc limit 1;

  if rid is not null then
    update reports set expires_at = greatest(expires_at, now() + life) where id = rid;
    return rid;
  end if;

  insert into reports (type, lat, lng, user_id, expires_at)
  values (p_type, p_lat, p_lng, uid, now() + life)
  returning id into rid;
  return rid;
end;
$$;

-- Live reports inside a map box. "mine" tells the app which ones the caller may delete.
create or replace function public.get_reports(min_lat double precision, min_lng double precision,
                                              max_lat double precision, max_lng double precision)
returns table (id uuid, type text, lat double precision, lng double precision,
               created_at timestamptz, mine boolean)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select r.id, r.type, r.lat, r.lng, r.created_at, coalesce(r.user_id = auth.uid(), false)
  from reports r
  where r.expires_at > now() and r.gone < 2
    and r.lat between min_lat and max_lat and r.lng between min_lng and max_lng
    and max_lat - min_lat between 0 and 1 and max_lng - min_lng between 0 and 1
  order by r.created_at desc
  limit 500;
$$;

-- The author deletes their report; anyone else votes "not there". Two votes hide it.
create or replace function public.remove_report(p_id uuid)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then raise exception 'sign in required'; end if;
  delete from reports where id = p_id and user_id = uid;
  if found then return 'deleted'; end if;
  insert into report_votes (report_id, user_id) values (p_id, uid) on conflict do nothing;
  if found then
    update reports set gone = gone + 1 where id = p_id;
  end if;
  return 'voted';
end;
$$;

-- ───────────────────────── Permissions ─────────────────────────

revoke execute on function public.submit_traffic(jsonb)                                                      from public, anon;
revoke execute on function public.get_traffic(double precision, double precision, double precision, double precision) from public;
revoke execute on function public.add_report(text, double precision, double precision)                      from public, anon;
revoke execute on function public.get_reports(double precision, double precision, double precision, double precision) from public;
revoke execute on function public.remove_report(uuid)                                                        from public, anon;

grant execute on function public.get_traffic(double precision, double precision, double precision, double precision) to anon, authenticated;
grant execute on function public.get_reports(double precision, double precision, double precision, double precision) to anon, authenticated;
grant execute on function public.submit_traffic(jsonb)                                    to authenticated;
grant execute on function public.add_report(text, double precision, double precision)    to authenticated;
grant execute on function public.remove_report(uuid)                                      to authenticated;
