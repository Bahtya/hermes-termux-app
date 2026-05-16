-- =============================================================
-- Hermes App 许可证认证系统 - Supabase 数据库 Schema
-- 在 Supabase SQL 编辑器中执行此文件
-- =============================================================

-- 许可证表
create table licenses (
    id              uuid primary key default gen_random_uuid(),
    license_key     text not null unique,
    key_hash        text not null unique,
    email           text,
    plan            text not null default 'lifetime',
    status          text not null default 'inactive',
    max_devices     int not null default 2,
    purchased_at    timestamptz,
    created_at      timestamptz not null default now(),
    notes           text
);

alter table licenses enable row level security;

create policy "仅服务角色可操作" on licenses for all
    using (auth.role() = 'service_role')
    with check (auth.role() = 'service_role');

-- 设备表
create table devices (
    id                  uuid primary key default gen_random_uuid(),
    license_id          uuid not null references licenses(id) on delete cascade,
    device_fingerprint  text not null,
    device_name         text,
    android_id          text,
    app_version         text,
    activated_at        timestamptz not null default now(),
    last_seen_at        timestamptz not null default now(),
    is_active           boolean not null default true,
    verification_token  text,
    unique(license_id, device_fingerprint)
);

alter table devices enable row level security;

create policy "仅服务角色可操作" on devices for all
    using (auth.role() = 'service_role')
    with check (auth.role() = 'service_role');

-- 激活事件日志
create table activation_events (
    id                  uuid primary key default gen_random_uuid(),
    license_id          uuid references licenses(id),
    device_id           uuid references devices(id),
    event_type          text not null,
    device_fingerprint  text,
    success             boolean not null default true,
    error_message       text,
    created_at          timestamptz not null default now()
);

alter table activation_events enable row level security;

create policy "仅服务角色可操作" on activation_events for all
    using (auth.role() = 'service_role')
    with check (auth.role() = 'service_role');

-- 索引
create index idx_licenses_key_hash on licenses(key_hash);
create index idx_licenses_status on licenses(status);
create index idx_devices_license on devices(license_id);
create index idx_devices_fingerprint on devices(device_fingerprint);
create index idx_events_created on activation_events(created_at);

-- 辅助函数：生成许可证密钥
create or replace function generate_license_key(
    p_email text default null,
    p_max_devices int default 2
) returns text language plpgsql as $$
declare
    raw_key text;
    key_hash_val text;
    chars text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
    seg text;
begin
    raw_key := 'HRMX';
    for i in 1..4 loop
        seg := '';
        for j in 1..4 loop
            seg := seg || substr(chars, floor(random() * length(chars)::numeric + 1)::int, 1);
        end loop;
        raw_key := raw_key || '-' || seg;
    end loop;
    key_hash_val := encode(digest(raw_key, 'sha256'), 'hex');
    insert into licenses (license_key, key_hash, email, plan, status, max_devices, purchased_at)
    values (raw_key, key_hash_val, p_email, 'lifetime', 'inactive', p_max_devices, now());
    return raw_key;
end;
$$;

-- 事件日志清理（配合 pg_cron 或手动执行）
-- DELETE FROM activation_events WHERE created_at < now() - interval '90 days';
