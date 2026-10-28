ALTER TABLE search_documents ADD COLUMN in_stock boolean NOT NULL DEFAULT true;
-- Coalesce an existing backlog before enforcing one pending refresh per product.
WITH duplicates AS (
    SELECT id, row_number() OVER (PARTITION BY product_id ORDER BY id DESC) AS position
    FROM search_index_jobs WHERE status IN ('QUEUED', 'RETRY')
)
DELETE FROM search_index_jobs WHERE id IN (SELECT id FROM duplicates WHERE position > 1);
CREATE UNIQUE INDEX idx_search_one_pending_product ON search_index_jobs(product_id)
    WHERE status IN ('QUEUED', 'RETRY');
CREATE INDEX idx_search_completed_retention ON search_index_jobs(updated_at) WHERE status='COMPLETED';

-- Rehydrate existing documents through the public catalog boundary after adding availability.
INSERT INTO search_index_jobs(product_id)
SELECT product_id FROM search_documents
ON CONFLICT (product_id) WHERE status IN ('QUEUED', 'RETRY') DO NOTHING;
