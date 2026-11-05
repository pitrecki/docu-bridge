-- Legacy fingerprints do not contain a recoverable content checksum. Leave those rows null.
alter table dokubridge_upload_requests
    add column expected_sha256 varchar(64),
    add constraint dokubridge_upload_checksum_check
        check (expected_sha256 is null or expected_sha256 ~ '^[0-9a-f]{64}$');

alter table dokubridge_audit drop constraint dokubridge_audit_action_context_check;
alter table dokubridge_audit add constraint dokubridge_audit_action_context_check check (
    (action in ('DOCUMENT_ATTACHED', 'VERSION_ADDED', 'UPLOAD_SUCCEEDED', 'UPLOAD_FAILED',
                'UPLOAD_RECOVERED', 'UPLOAD_RECOVERY_FAILED')
        and version_id is not null and request_id is not null)
    or
    (action in ('METADATA_UPDATED', 'DOCUMENT_ARCHIVED', 'DOCUMENT_DELETED')
        and version_id is null and request_id is null)
);
