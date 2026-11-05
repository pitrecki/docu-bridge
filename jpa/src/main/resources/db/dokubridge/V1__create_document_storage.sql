create table dokubridge_documents (
    id uuid primary key,
    owner_type text not null check (length(btrim(owner_type)) > 0),
    owner_id text not null check (length(btrim(owner_id)) > 0),
    metadata jsonb not null check (jsonb_typeof(metadata) = 'object'),
    state varchar(16) not null check (state in ('ACTIVE', 'ARCHIVED', 'DELETED')),
    last_version integer not null check (last_version > 0)
);
create index dokubridge_documents_owner on dokubridge_documents (owner_type, owner_id);

create table dokubridge_versions (
    id uuid primary key,
    document_id uuid not null references dokubridge_documents(id),
    version_number integer not null check (version_number > 0),
    state varchar(16) not null check (state in ('PENDING', 'AVAILABLE', 'FAILED')),
    unique (document_id, version_number)
);

create table dokubridge_upload_requests (
    id uuid primary key,
    fingerprint varchar(64) not null check (fingerprint ~ '^[0-9a-f]{64}$'),
    version_id uuid not null unique,
    constraint dokubridge_request_version foreign key (version_id)
        references dokubridge_versions(id) deferrable initially deferred
);

create table dokubridge_audit (
    id uuid primary key,
    document_id uuid not null references dokubridge_documents(id),
    version_id uuid not null references dokubridge_versions(id),
    request_id uuid not null references dokubridge_upload_requests(id),
    action varchar(32) not null check (action in
        ('DOCUMENT_ATTACHED', 'VERSION_ADDED', 'UPLOAD_SUCCEEDED', 'UPLOAD_FAILED')),
    occurred_at timestamptz not null,
    position bigint generated always as identity unique
);
create index dokubridge_audit_document on dokubridge_audit (document_id, position);
