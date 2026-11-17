-- A report revision owns its plan reference and any evidence still being drafted.
ALTER TABLE checkpoint_report_items
    ADD COLUMN plan_revision_id uuid,
    ADD COLUMN pending_evidence jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN pending_result_note text,
    ADD CONSTRAINT fk_report_item_plan FOREIGN KEY (round_id, plan_revision_id)
        REFERENCES task_plan_revisions (round_id, id);

-- Do not backfill submitted items from a mutable pre-cutoff snapshot: old plans may be ambiguous.
CREATE TABLE workflow_requests (
    project_id uuid NOT NULL REFERENCES projects(id),
    user_id uuid NOT NULL REFERENCES users(id),
    request_key varchar(200) NOT NULL,
    operation varchar(200) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, operation, request_key)
);

ALTER TABLE task_plan_revisions DROP CONSTRAINT task_plan_revisions_status_chk;
ALTER TABLE task_plan_revisions ADD CONSTRAINT task_plan_revisions_status_chk
    CHECK (status IN ('DRAFT','PENDING_APPROVAL','CHANGES_REQUESTED','APPROVED','CANCELLED'));
ALTER TABLE task_plan_revisions DROP CONSTRAINT task_plan_revisions_complete_chk;
ALTER TABLE task_plan_revisions ADD CONSTRAINT task_plan_revisions_complete_chk CHECK (
    status IN ('DRAFT','CHANGES_REQUESTED','CANCELLED') OR (
    assignee_id IS NOT NULL AND proposed_cycle_id IS NOT NULL AND title IS NOT NULL AND btrim(title) <> ''
    AND description IS NOT NULL AND btrim(description) <> '' AND expected_output IS NOT NULL AND btrim(expected_output) <> ''
    AND start_at IS NOT NULL AND deadline IS NOT NULL AND difficulty IS NOT NULL AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL));
DROP INDEX uq_task_plan_revisions_one_open;
CREATE UNIQUE INDEX uq_task_plan_revisions_one_open ON task_plan_revisions(round_id)
    WHERE status IN ('DRAFT','PENDING_APPROVAL','CHANGES_REQUESTED');

ALTER TABLE task_carryovers ADD COLUMN source_report_item_id uuid REFERENCES checkpoint_report_items(id);
