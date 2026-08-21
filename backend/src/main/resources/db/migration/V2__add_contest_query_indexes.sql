CREATE INDEX idx_contest_date_id
    ON contest (contest_date, id);

CREATE INDEX idx_contest_region_date_id
    ON contest (region, contest_date, id);
