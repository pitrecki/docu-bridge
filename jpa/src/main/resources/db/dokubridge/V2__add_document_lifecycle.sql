alter table dokubridge_documents
    add column revision bigint not null default 0 check (revision >= 0);

alter table dokubridge_audit
    alter column version_id drop not null,
    alter column request_id drop not null,
    drop constraint dokubridge_audit_action_check;

alter table dokubridge_audit add constraint dokubridge_audit_action_context_check check (
    (action in ('DOCUMENT_ATTACHED', 'VERSION_ADDED', 'UPLOAD_SUCCEEDED', 'UPLOAD_FAILED')
        and version_id is not null and request_id is not null)
    or
    (action in ('METADATA_UPDATED', 'DOCUMENT_ARCHIVED', 'DOCUMENT_DELETED')
        and version_id is null and request_id is null)
);
