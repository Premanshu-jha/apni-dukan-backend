-- With several copies of the backend running, each background job should run on one of them at a time. A job takes
-- its row's lease (locked_until in the future) before running; the others see it taken and skip that round. A copy
-- that dies mid-run frees the job when its lease runs out.
CREATE TABLE job_leases (
    name          VARCHAR(40)   PRIMARY KEY,
    locked_until  TIMESTAMPTZ   NOT NULL,
    locked_by     VARCHAR(80)   NOT NULL,
    locked_at     TIMESTAMPTZ   NOT NULL
);
