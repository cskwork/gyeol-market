-- Existing accounts predate MemberRegistered; new accounts receive the same grant via the outbox.
INSERT INTO member_coupon (member_id, coupon_id, status, issued_at, expires_at)
SELECT m.id, c.id, 'ISSUED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '90 days'
FROM members m CROSS JOIN coupon c
WHERE c.id IN (1, 2, 3)
ON CONFLICT (member_id, coupon_id) DO NOTHING;
